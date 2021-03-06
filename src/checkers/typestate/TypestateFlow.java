package checkers.typestate;

import checkers.flow.MainFlow;
import checkers.flow.GenKillBits;
import checkers.types.AnnotatedTypeMirror;
import checkers.types.AnnotatedTypeFactory;
import checkers.util.InternalUtils;
import checkers.util.AnnotationUtils;
import checkers.util.TreeUtils;
import checkers.source.Result;
import checkers.source.SourceChecker;
import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import java.util.Set;
import java.util.Map;
import java.util.Iterator;
import java.util.HashMap;

/**
 * @author Adam Warski (adam at warski dot org)
 */
public class TypestateFlow extends MainFlow {
    // Because AnnotationMirror doesn't implement .equals and .hashCode, a translation map is needed from
    // an annotation mirror that is "equal" to some annotation present int the <code>annotations</code>
    // set, to the annotation mirror used in the <code>GenKillBits</code> set.
    private final Map<AnnotationMirror, AnnotationMirror> annotationsTranslation;

    private final TypestateUtil typestateUtil;

	// The transition element which should be read.
	protected TransitionElement transitionElement = TransitionElement.AFTER;

    public TypestateFlow(SourceChecker checker, Set<AnnotationMirror> annotations, AnnotatedTypeFactory factory,
                         CompilationUnitTree root, TypestateUtil typestateUtil) {
        super(checker, root, annotations, factory);

        this.typestateUtil = typestateUtil;

        // This will work because the map uses special ordering.
        annotationsTranslation = AnnotationUtils.createAnnotationMap();
        for (AnnotationMirror stateAnnotation : annotations) {
            annotationsTranslation.put(stateAnnotation, stateAnnotation);
        }
    }

	private AnnotationMirror translateToErrorAnnotation(final AnnotationMirror annotation) {
		if (typestateUtil.isAnyStateAnnotation(annotation)) {
			// Creating the same any-state annotation, with the "except" element set, and other elements removed.
			AnnotationUtils.AnnotationBuilder builder = new AnnotationUtils.AnnotationBuilder(env,
					new AnnotationMirror() {
						@Override
						public DeclaredType getAnnotationType() {
							return annotation.getAnnotationType();
						}

						@Override
						public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
							Map<? extends ExecutableElement, ? extends AnnotationValue> originalValues =
									annotation.getElementValues();
							Map<? extends ExecutableElement, ? extends AnnotationValue> modifiedValues =
									new HashMap<ExecutableElement, AnnotationValue>(originalValues);

							// Only keeping the "except" element, if at all it's present.
							Iterator<? extends ExecutableElement> elementsIterator = modifiedValues.keySet().iterator();
					        while (elementsIterator.hasNext()) {
								if (!elementsIterator.next().getSimpleName().contentEquals(TypestateUtil.EXCEPT_ELEMENT_NAME)) {
									elementsIterator.remove();
								}
							}

							return modifiedValues;
						}
					});

			return builder.build();
		}

		// The annotation surely is a state annotation
		assert annotationsTranslation.containsKey(annotation);
		return annotationsTranslation.get(annotation);
	}

    private Object getErrorAnnotationSetRepresentation(Set<AnnotationMirror> annotations, boolean translate) {
        if (annotations.size() == 0) {
            return "none";
        } else if (annotations.size() == 1) {
            return translate ? translateToErrorAnnotation(annotations.iterator().next()) : annotations.iterator().next();
        } else {
            if (translate) {
                Set<AnnotationMirror> translated = AnnotationUtils.createAnnotationSet();
                for (AnnotationMirror annotation : annotations) {
                    translated.add(translateToErrorAnnotation(annotation));
                }

                return translated;
            } else {
                return annotations;
            }
        }
    }

	private void clearStateAnnotation(AnnotationMirror declaredAnnotation, int elementIdx,
									  GenKillBits<AnnotationMirror> annos) {
		AnnotationMirror receiverAnnTranslation = annotationsTranslation.get(declaredAnnotation);

		// If the "after" annotation is a state annotation, changing the state of the
		// element in the flow.
		if (annotations.contains(receiverAnnTranslation)) {
			// The annotation didn't have to be set, if the transition is caused by the any-state
			// annotation.
			annos.clear(receiverAnnTranslation, elementIdx);
		}
	}

    private void checkStateAnnotationsOnTree(Set<AnnotationMirror> declaredAnnotations, Tree annotatedTree,
                                             MethodInvocationTree methodInvocationTree, String errorMessageKey) {
        // Only checking the state if the declaration specifies any state
        if (declaredAnnotations.size() > 0) {
            Element annotatedElement = InternalUtils.symbol(annotatedTree);

            // Generating the "actual" annotations of the element.
            Set<AnnotationMirror> actualAnnotations = AnnotationUtils.createAnnotationSet();

            // If the element is a variable, getting all annotations currently inferred by the flow.
            @SuppressWarnings({"SuspiciousMethodCalls"}) int elementIdx = vars.indexOf(annotatedElement);
            if (elementIdx >= 0) {
                for (AnnotationMirror stateAnnotation : annotations) {
                    if (annos.get(stateAnnotation, elementIdx)) {
                        actualAnnotations.add(stateAnnotation);
                    }
                }
            } else {
                // Otherwise, adding all annotations which the factory can infer on the element.
                for (AnnotationMirror factoryAnnotation : factory.getAnnotatedType(annotatedTree).getAnnotations()) {
                    // Only adding state annotations
                    if (annotations.contains(factoryAnnotation)) {
                        actualAnnotations.add(factoryAnnotation);
                    }
                }
            }

            boolean stateMatchFound = false;

            // For all declared annotations: if such an annotation is a state annotation, checking if the
            // checked element is in this state. If so, doing possible transitions.
            for (AnnotationMirror declaredAnnotation : declaredAnnotations) {
                // Checking if the declared annotation is a state annotation, which is also present on the element
                // checked, or if it is the any-state annotation, and the actual annotations aren't in the
				// "except" parameter of the annotation.
                // "contains" here is ok as we use the special annotation set (annotation parameter values are ignored).
                if ((annotations.contains(declaredAnnotation) && actualAnnotations.contains(declaredAnnotation))
                        || typestateUtil.anyAnnotationCovers(declaredAnnotation, actualAnnotations)) {
                    stateMatchFound = true;

					// First checking if we are in a try-catch-finally. If so, looking for an exception annotation. If
					// it is present, updating the try bits to be in the new state.
					if (tryBits.size() > 0 || catchBits.size() > 0) {
						AnnotationMirror exceptionAnnotation = typestateUtil.getExceptionElementValue(
								declaredAnnotation);

						if (exceptionAnnotation != null) {
							// Preparing an annotations bits set with the exception state set
							GenKillBits<AnnotationMirror> exceptionBits = GenKillBits.copy(annos);
							clearStateAnnotation(declaredAnnotation, elementIdx, exceptionBits);
							exceptionBits.set(annotationsTranslation.get(exceptionAnnotation), elementIdx);

							// And updating the exception bits
							updateExceptionBits(exceptionBits);
						}
					}

					// Trying to read the specific transition element
                    AnnotationMirror afterAnnotation = typestateUtil.getTransitionElementValue(declaredAnnotation, transitionElement);
					// If no value was found, and the element wasn't the normal one ('after'), trying to read it.
					if (afterAnnotation == null && transitionElement != TransitionElement.AFTER) {
						afterAnnotation = typestateUtil.getTransitionElementValue(declaredAnnotation, TransitionElement.AFTER);
					}
                    // Currently the transitions will only work for variables - hence checking the elementIdx.
                    if (elementIdx >= 0 && afterAnnotation != null && annotations.contains(afterAnnotation)) {
                        // If the "after" annotation is a state annotation, changing the state of the
                        // element in the flow.

						// Clearing any of the old states
						for (AnnotationMirror actualAnnotation : actualAnnotations) {
							clearStateAnnotation(actualAnnotation, elementIdx, annos);
						}

						// Setting the new state
                        annos.set(annotationsTranslation.get(afterAnnotation), elementIdx);
                    }
                }
            }

            // If none of the actual states matches the declared states, reporting an error.
            if (!stateMatchFound) {
                checker.report(Result.failure(errorMessageKey, annotatedTree,
                        // The declared annotations must be translated to their representation as they may
                        // contain elements - users shouldn't see that in the error message.
                        getErrorAnnotationSetRepresentation(declaredAnnotations, true),
                        getErrorAnnotationSetRepresentation(actualAnnotations, false)),
                        methodInvocationTree);
            }
        }
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        AnnotatedTypeMirror.AnnotatedExecutableType invocationType = factory.methodFromUse(node);

        // Checking the receiver
        Set<AnnotationMirror> receiverAnnotations = invocationType.getReceiverType().getAnnotations();

        if (node.getMethodSelect().getKind() == Tree.Kind.MEMBER_SELECT) {
            checkStateAnnotationsOnTree(typestateUtil.filterStateAnnotations(receiverAnnotations),
                    ((MemberSelectTree) node.getMethodSelect()).getExpression(),
                    node, "receiver.in.wrong.state");
        }

        // Checking parameters; both iterators should have the same number of elements.
        Iterator<AnnotatedTypeMirror> parametersAnnotationsIter = invocationType.getParameterTypes().iterator();
        Iterator<? extends ExpressionTree> argumentsIter = node.getArguments().iterator();
        while (parametersAnnotationsIter.hasNext()) {
            checkStateAnnotationsOnTree(
					typestateUtil.filterStateAnnotations(parametersAnnotationsIter.next().getAnnotations()),
					argumentsIter.next(), node, "parameter.in.wrong.state");
        }

        return super.visitMethodInvocation(node, p);
    }

	@Override
	protected void updateExceptionBits() {
		// Exception states are handled already. Doing nothing here.
	}

	@Override
    protected void scanCond(Tree tree) {
		// So far only simple or negated comparisions of a method call to a constant are supported.
		boolean supported = isSupportedLogic(tree);

		if (!supported) {
			super.scanCond(tree);
			return;
		}

		if (tree == null) {
			return;
		}

		GenKillBits<AnnotationMirror> before = GenKillBits.copy(annos);

		// Scanning the condition twice: once with the after-true element active, once with the active-false element
		// active.
		transitionElement = TransitionElement.AFTER_TRUE;
		alive = true;
        scan(tree, null);
		GenKillBits<AnnotationMirror> afterTrue = annos;

		transitionElement = TransitionElement.AFTER_FALSE;
		annos = before;
		alive = true;
		scan(tree, null);
		GenKillBits<AnnotationMirror> afterFalse = annos;

		transitionElement = TransitionElement.AFTER;

		// Now splitting the annotation set appropriately
		if (annos != null) {
			// In case of a complement, we have to switch the true and false
			if (inverted(tree)) {
				annosWhenTrue = afterFalse;
				annosWhenFalse = afterTrue;
			} else {
				annosWhenTrue = afterTrue;
				annosWhenFalse = afterFalse;
			}
			
			annos = null;
		}
    }

	/**
	 * @param tree Tree to check.
	 * @return True if the result of the method is checked to be false (not true).
	 */
	private static boolean inverted(Tree tree) {
		tree = TreeUtils.skipParens(tree);
		final boolean[] inverted = new boolean[1];
		switch (tree.getKind()) {
			case METHOD_INVOCATION:
				return false;
			case EQUAL_TO:
				inverted[0] = false;
				break;
			case NOT_EQUAL_TO:
				inverted[0] = true;
				break;
			case LOGICAL_COMPLEMENT:
				return !inverted(((UnaryTree)tree).getExpression());
			default:
				throw new RuntimeException("Unsupported tree kind: " + tree.getKind());
		}

		// Now checking if the constant to which the method invocation is compared is true or false.
		// In case of false - we have to invert the result.
		tree.accept(new TreeScanner<Void, Void>() {
			@Override
			public Void visitLiteral(LiteralTree node, Void aVoid) {
				Object value = node.getValue();
				if (value instanceof Boolean && !((Boolean) value)) {
					inverted[0] = !inverted[0];
				}
				return null;
			}
		}, null);


		return inverted[0];
	}

	/**
     * Copied and adapted from <code>NullnessFlow</code> as it is private there.
	 *
	 * @param tree The tree to check.
	 * @return True if the logical statement in the given tree is supported.
     */
	private static boolean isSupportedLogic(Tree tree) {
		tree = TreeUtils.skipParens(tree);
		// First checking the kind of the tree
		switch (tree.getKind()) {
			case EQUAL_TO:
			case NOT_EQUAL_TO:
				break;			
			case METHOD_INVOCATION:
				return true;
			case LOGICAL_COMPLEMENT:
				return isSupportedLogic(((UnaryTree)tree).getExpression());
			default:
				return false;
		}

		// And now checking that the tree contains a method invocation and a constant
		final int[] methodInvoked = new int[]{0};
		final int[] constant = new int[]{0};

		tree.accept(new TreeScanner<Void, Void>() {
			@Override
			public Void visitMethodInvocation(MethodInvocationTree node, Void aVoid) {
				methodInvoked[0]++;
				return null;
			}

			@Override
			public Void visitLiteral(LiteralTree node, Void aVoid) {
				constant[0]++;
				return null;
			}
		}, null);

		return methodInvoked[0] == 1 && constant[0] == 1;
	}
}
