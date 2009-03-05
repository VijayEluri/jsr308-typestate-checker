package checkers.flow;

import checkers.source.SourceChecker;
import checkers.types.*;
import checkers.types.AnnotatedTypeMirror.*;
import checkers.util.*;

import java.io.PrintStream;
import java.util.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;

/**
 * A modified version of {@link Flow}.
 *
 * Should report errors during the flow analysis, as no information is recorded for later retrieval.
 * Also, subtyping isn't checked in any way.
 *
 * Detailed changes:
 * - removed the {@code QualifierHierarchy annoRelations} field and all its usages
 * - removed the {@code AnnotationMirror test(Tree tree)} method
 * - removed the {@code Map<Location, AnnotationMirror> flowResults} field and all its usages
 * - removed the {@code void recordBits(TreePath path)} method and all its usages
 *
 * @author Adam Warski (adam at warski dot org)
 * @author The authors of the {@link Flow} class.
 */
public abstract class MainFlow extends TreePathScanner<Void, Void> {

    /** Where to print debugging messages; set via {@link #setDebug}. */
    private PrintStream debug = null;

    /** The checker to which this instance belongs. */
    protected final SourceChecker checker;

    /** The processing environment to use. */
    protected final ProcessingEnvironment env;

    /** The file that's being analyzed. */
    protected final CompilationUnitTree root;

    /** The annotations (qualifiers) to infer. */
    protected final Set<AnnotationMirror> annotations;

    /** Utility class for getting source positions. */
    protected final SourcePositions source;

    /** Utility class for determining annotated types. */
    protected final AnnotatedTypeFactory factory;

    /** Utility class for operations on annotated types. */
    protected final AnnotatedTypes atypes;

    /**
     * Maps variables to a bit index. This index is also used as the bit index
     * to determine a variable's annotatedness using
     * annos/annosWhenTrue/annosWhenFalse.
     */
    protected final List<VariableElement> vars;

    /**
     * Tracks the annotated state of each variable during flow. Bit indices
     * correspond exactly to indices in {@link #vars}. This field is set to
     * null immediately after splitting for a branch, and is set to some
     * combination (usually boolean "and") of {@link #annosWhenTrue} and
     * {@link #annosWhenFalse} after merging. Since it is used when visiting the
     * true and false branches, however, it may be non-null concurrently with
     * {@link #annosWhenTrue} and {@link #annosWhenFalse}.
     */
    protected GenKillBits<AnnotationMirror> annos;

    /**
     * Tracks the annotated state of each variable in a true branch. As in
     * {@code javac}'s {@code Flow}, saving/restoring via local variables
     * handles nested branches. Bit indices correspond exactly to indices in
     * {@link #vars}. This field is copied from {@link #annos} when splitting
     * for a branch and is set to null immediately after merging.
     *
     * @see #annos
     */
    protected GenKillBits<AnnotationMirror> annosWhenTrue;

    /**
     * Tracks the annotated state of each variable in a false branch. As in
     * {@code javac}'s {@code Flow}, saving/restoring via local variables
     * handles nested branches. Bit indices correspond exactly to indices in
     * {@link #vars}. This field is copied from {@link #annos} when splitting
     * for a branch and is set to null immediately after merging.
     *
     * @see #annos
     */
    protected GenKillBits<AnnotationMirror> annosWhenFalse;

    /**
     * Stores the result of liveness analysis, required by the GEN-KILL analysis
     * for proper handling of jumps (break, return, throw, etc.).
     */
    private boolean alive = true;

    /** Tracks annotations in try blocks to support exceptions. */
    private final Deque<GenKillBits<AnnotationMirror>> tryBits;

    /** Visitor state; tracking is required for checking receiver types. */
    private final VisitorState visitorState;

    /** Utilities for {@link javax.lang.model.element.Element}s. */
    protected final Elements elements;

    /** Memoization for {@link #varDefHasAnnotation(javax.lang.model.element.AnnotationMirror, javax.lang.model.element.Element)}. */
    private Map<Element, Boolean> annotatedVarDefs = new HashMap<Element, Boolean>();

    /**
     * Creates a new analysis. The analysis will use the given {@link
     * checkers.types.AnnotatedTypeFactory} to obtain annotated types.
     *
     * @param checker the current checker
     * @param root the compilation unit that will be scanned
     * @param annotations the annotations to track
     * @param factory the factory class that will be used to get annotated
     *        types, or {@code null} if the default factory should be used
     */
    public MainFlow(SourceChecker checker, CompilationUnitTree root,
            Set<AnnotationMirror> annotations, AnnotatedTypeFactory factory) {

        this.checker = checker;
        this.env = checker.getProcessingEnvironment();
        this.root = root;
        this.annotations = annotations;

        this.source = Trees.instance(env).getSourcePositions();
        if (factory == null)
            this.factory = new AnnotatedTypeFactory(checker, root);
        else this.factory = factory;

        this.atypes = new AnnotatedTypes(env, factory);

        this.visitorState = this.factory.getVisitorState();

        this.vars = new ArrayList<VariableElement>();

        this.annos = new GenKillBits<AnnotationMirror>(this.annotations);
        this.annosWhenTrue = null;
        this.annosWhenFalse = null;

        this.tryBits = new LinkedList<GenKillBits<AnnotationMirror>>();

        elements = env.getElementUtils();
    }

    /**
     * Sets the {@link java.io.PrintStream} for printing debug messages, such as
     * {@link System#out} or {@link System#err}, or null if no debugging output
     * should be emitted.
     */
    public void setDebug(PrintStream debug) {
        this.debug = debug;
    }

    @Override
    public Void scan(Tree tree, Void p) {
        if (tree != null && getCurrentPath() != null)
            this.visitorState.setPath(new TreePath(getCurrentPath(), tree));
        return super.scan(tree, p);
    }

    /**
     * Registers a new variable for flow tracking.
     *
     * @param tree the variable to register
     */
    void newVar(VariableTree tree) {

        VariableElement var = TreeUtils.elementFromDeclaration(tree);
        assert var != null : "no symbol from tree";

        if (vars.contains(var)) {
            if (debug != null)
                debug.println("Flow: newVar(" + tree + ") reusing index");
            return;
        }

        int idx = vars.size();
        vars.add(var);

        AnnotatedTypeMirror type = factory.getAnnotatedType(tree);
        assert type != null : "no type from symbol";

        if (debug != null)
            debug.println("Flow: newVar(" + tree + ") -- " + type);

        // Determine the initial status of the variable by checking its
        // annotated type.
        for (AnnotationMirror annotation : annotations) {
            if (hasAnnotation(type, annotation))
                annos.set(annotation, idx);
            else
                annos.clear(annotation, idx);
        }
    }

    /**
     * Determines whether a type has an annotation. If the type is not a
     * wildcard, it checks the type directly; if it is a wildcard, it checks the
     * wildcard's "extends" bound (if it has one).
     *
     * @param type the type to check
     * @param annotation the annotation to check for
     * @return true if the (non-wildcard) type has the annotation or, if a
     *         wildcard, the type has the annotation on its extends bound
     */
    private boolean hasAnnotation(AnnotatedTypeMirror type,
            AnnotationMirror annotation) {
        if (!(type instanceof AnnotatedWildcardType))
            return type.hasAnnotation(annotation);
        AnnotatedWildcardType wc = (AnnotatedWildcardType) type;
        AnnotatedTypeMirror bound = wc.getExtendsBound();
        if (bound != null && bound.hasAnnotation(annotation))
            return true;
        return false;
    }

    /**
     * Moves bits as assignments are made.
     *
     * <p>
     *
     * If only type information (and not a {@link com.sun.source.tree.Tree}) is available, use
     * {@link #propagateFromType(com.sun.source.tree.Tree, checkers.types.AnnotatedTypeMirror)} instead.
     *
     * @param lhs the left-hand side of the assignment
     * @param rhs the right-hand side of the assignment
     */
    void propagate(Tree lhs, ExpressionTree rhs) {

        if (debug != null)
            debug.println("Flow: try propagate from " + rhs);

        // Skip assignment to arrays.
        if (lhs.getKind() == Tree.Kind.ARRAY_ACCESS)
            return;

        // Get the element for the left-hand side.
        Element elt = InternalUtils.symbol(lhs);
        assert elt != null;

        // Get the annotated type of the right-hand side.
        AnnotatedTypeMirror type = factory.getAnnotatedType(rhs);
        if (TreeUtils.skipParens(rhs).getKind() == Tree.Kind.ARRAY_ACCESS) {
            propagateFromType(lhs, type);
            return;
        }
        assert type != null;

        int idx = vars.indexOf(elt);
        if (idx < 0) return;

        // Get the element for the right-hand side.
        Element rElt = InternalUtils.symbol(rhs);
        int rIdx = vars.indexOf(rElt);

        for (AnnotationMirror annotation : annotations) {
            // Propagate/clear the annotation if it's annotated or an annotation
            // had been inferred previously.
            if (hasAnnotation(type, annotation) || (rIdx >= 0 && annos.get(annotation, rIdx)))
                annos.set(annotation, idx);
            else annos.clear(annotation, idx);
        }
    }

    /**
     * Moves bits in an assignment using a type instead of a tree.
     *
     * <p>
     *
     * {@link #propagate(com.sun.source.tree.Tree, com.sun.source.tree.ExpressionTree)} is preferred, since it is able to use
     * extra information about the right-hand side (such as its element). This
     * method should only be used when a type (and nothing else) is available,
     * such as when checking the variable in an enhanced for loop against the
     * iterated type (which is the type argument of an {@link Iterable}).
     *
     * @param lhs the left-hand side of the assignment
     * @param rhs the type of the right-hand side of the assignment
     */
    void propagateFromType(Tree lhs, AnnotatedTypeMirror rhs) {

        if (lhs.getKind() == Tree.Kind.ARRAY_ACCESS)
            return;

        Element elt = InternalUtils.symbol(lhs);

        int idx = vars.indexOf(elt);
        if (idx < 0) return;

        for (AnnotationMirror annotation : annotations) {
            if (hasAnnotation(rhs, annotation))
                annos.set(annotation, idx);
            else annos.clear(annotation, idx);
        }
    }

    /**
     * Split the bitset before a conditional branch.
     */
    void split() {
        annosWhenFalse = GenKillBits.copy(annos);
        annosWhenTrue = annos;
        annos = null;
    }

    /**
     * Merge the bitset after a conditional branch.
     */
    void merge() {
        annos = GenKillBits.copy(annos);
        annos.and(annosWhenFalse);
        annosWhenTrue = annosWhenFalse = null;
    }

    // **********************************************************************

    /**
     * Called whenever a definition is scanned.
     *
     * @param tree the definition being scanned
     */
    protected void scanDef(Tree tree) {
        alive = true;
        scan(tree, null);
    }

    /**
     * Called whenever a statement is scanned.
     *
     * @param tree the statement being scanned
     */
    protected void scanStat(StatementTree tree) {
        alive = true;
        scan(tree, null);
    }

    /**
     * Called whenever a block of statements is scanned.
     *
     * @param trees the statements being scanned
     */
    protected void scanStats(List<? extends StatementTree> trees) {
        scan(trees, null);
    }

    /**
     * Called whenever a conditional expression is scanned.
     *
     * @param tree the condition being scanned
     */
    protected void scanCond(Tree tree) {
        alive = true;
        scan(tree, null);
        if (annos != null) split();
        annos = null;
    }

    /**
     * Called whenever an expression is scanned.
     *
     * @param tree the expression being scanned
     */
    protected void scanExpr(ExpressionTree tree) {
        alive = true;
        scan(tree, null);
        if (annos == null) merge();
    }

    // **********************************************************************

    @Override
    public Void visitClass(ClassTree node, Void p) {
        AnnotatedDeclaredType preClassType = visitorState.getClassType();
        ClassTree preClassTree = visitorState.getClassTree();
        AnnotatedDeclaredType preAMT = visitorState.getMethodReceiver();
        MethodTree preMT = visitorState.getMethodTree();

        visitorState.setClassType(factory.getAnnotatedType(node));
        visitorState.setClassTree(node);
        visitorState.setMethodReceiver(null);
        visitorState.setMethodTree(null);

        try {
            scan(node.getModifiers(), p);
            scan(node.getTypeParameters(), p);
            scan(node.getExtendsClause(), p);
            scan(node.getImplementsClause(), p);
            // Ensure that all fields are scanned before scanning methods.
            for (Tree t : node.getMembers()) {
                if (t.getKind() == Tree.Kind.METHOD) continue;
                scan(t, p);
            }
            for (Tree t : node.getMembers()) {
                if (t.getKind() != Tree.Kind.METHOD) continue;
                scan(t, p);
            }
            return null;
        } finally {
            this.visitorState.setClassType(preClassType);
            this.visitorState.setClassTree(preClassTree);
            this.visitorState.setMethodReceiver(preAMT);
            this.visitorState.setMethodTree(preMT);
        }
    }

    @Override
    public Void visitImport(ImportTree tree, Void p) {
        return null;
    }

    @Override
    public Void visitInstanceOf(InstanceOfTree tree, Void p) {
        super.visitInstanceOf(tree, p);

        ExpressionTree expr = tree.getExpression();

        Element elt = null;
        if (expr instanceof IdentifierTree)
            elt = TreeUtils.elementFromUse((IdentifierTree) expr);
        else if (expr instanceof MemberSelectTree)
            elt = TreeUtils.elementFromUse((MemberSelectTree) expr);

        if (elt != null && vars.contains(elt)) {
            int idx = vars.indexOf(elt);
            for (AnnotationMirror annotation : annotations)
                if (hasAnnotation(factory.getAnnotatedTypeFromTypeTree(tree.getType()), annotation))
                    annos.set(annotation, idx);
        }

        return null;
    }

    @Override
    public Void visitTypeCast(TypeCastTree node, Void p) {
        super.visitTypeCast(node, p);
        if (!factory.fromTypeTree(node.getType()).getAnnotations().isEmpty())
            return null;
        return null;
    }

    @Override
    public Void visitAnnotation(AnnotationTree tree, Void p) {
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void p) {
        super.visitIdentifier(node, p);
        return null;
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void p) {
        super.visitMemberSelect(node, p);
        return null;
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        newVar(node);
        ExpressionTree init = node.getInitializer();
        if (init != null) {
            scanExpr(init);
            VariableElement elem = TreeUtils.elementFromDeclaration(node);
            AnnotatedTypeMirror type = factory.fromMember(node);
            if (!isNonFinalField(elem) && type.getAnnotations().isEmpty()) {
                propagate(node, init);
            }
        }
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void p) {
        ExpressionTree var = node.getVariable();
        ExpressionTree expr = node.getExpression();
        if (!(var instanceof IdentifierTree))
            scanExpr(var);
        scanExpr(expr);
        propagate(var, expr);
        return null;
    }

    // This is an exact copy of visitAssignment()
    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void p) {
        ExpressionTree var = node.getVariable();
        ExpressionTree expr = node.getExpression();
        if (!(var instanceof IdentifierTree))
            scanExpr(var);
        scanExpr(expr);
        propagate(var, expr);
        return null;
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void p) {
        VariableTree var = node.getVariable();
        newVar(var);

        ExpressionTree expr = node.getExpression();
        scanExpr(expr);

        AnnotatedTypeMirror rhs = factory.getAnnotatedType(expr);
        AnnotatedTypeMirror iter = atypes.getIteratedType(rhs);
        if (iter != null)
            propagateFromType(var, iter);

        return super.visitEnhancedForLoop(node, p);
    }

    @Override
    public Void visitAssert(AssertTree node, Void p) {
        scanCond(node.getCondition());
        annos = GenKillBits.copy(annosWhenTrue);
        return null;
    }

    @Override
    public Void visitIf(IfTree node, Void p) {
        scanCond(node.getCondition());

        GenKillBits<AnnotationMirror> before = annosWhenFalse;
        annos = annosWhenTrue;

        boolean aliveBefore = alive;

        scanStat(node.getThenStatement());
        StatementTree elseStmt = node.getElseStatement();
        if (elseStmt != null) {
            boolean aliveAfter = alive;
            alive = aliveBefore;
            GenKillBits<AnnotationMirror> after = GenKillBits.copy(annos);
            annos = before;
            scanStat(elseStmt);
            alive &= aliveAfter;
            if (!alive)
                annos = GenKillBits.copy(after);
            else
                annos.and(after);
        } else {
            alive &= aliveBefore;
            if (!alive)
                annos = GenKillBits.copy(before);
            else
                annos.and(before);
        }

        return null;
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree node,
            Void p) {

        // Split and merge as for an if/else.
        scanCond(node.getCondition());

        GenKillBits<AnnotationMirror> before = annosWhenFalse;
        annos = annosWhenTrue;

        scanExpr(node.getTrueExpression());
        GenKillBits<AnnotationMirror> after = GenKillBits.copy(annos);
        annos = before;

        scanExpr(node.getFalseExpression());
        annos.and(after);

        return null;
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree node, Void p) {
        boolean pass = false;
        GenKillBits<AnnotationMirror> annoCond;
        do {
            GenKillBits<AnnotationMirror> annoEntry = GenKillBits.copy(annos);
            scanCond(node.getCondition());
            annoCond = annosWhenFalse;
            annos = annosWhenTrue;
            scanStat(node.getStatement());
            if (pass) break;
            annosWhenTrue.and(annoEntry);
            pass = true;
        } while (true);
        annos = annoCond;
        return null;
    }

    @Override
    public Void visitBreak(BreakTree node, Void p) {
        alive = false;
        return null;
    }

    @Override
    public Void visitContinue(ContinueTree node, Void p) {
        alive = false;
        return null;
    }

    @Override
    public Void visitReturn(ReturnTree node, Void p) {
        if (node.getExpression() != null)
            scanExpr(node.getExpression());
        alive = false;
        return null;
    }

    @Override
    public Void visitThrow(ThrowTree node, Void p) {
        scanExpr(node.getExpression());
        alive = false;
        return null;
    }

    @Override
    public Void visitTry(TryTree node, Void p) {
    tryBits.push(GenKillBits.copy(annos));
    scan(node.getBlock(), p);
    GenKillBits<AnnotationMirror> annoAfterBlock = GenKillBits.copy(annos);
    GenKillBits<AnnotationMirror> result = tryBits.pop();
    annos.and(result);
    if (node.getCatches() != null) {
        boolean catchAlive = true;
        for (CatchTree ct : node.getCatches()) {
            scan(ct, p);
            catchAlive &= alive;
        }
        // Conservative: only if there's no finally
        if (!catchAlive && node.getFinallyBlock() == null)
            annos = GenKillBits.copy(annoAfterBlock);
    }
    scan(node.getFinallyBlock(), p);
    return null;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        super.visitMethodInvocation(node, p);

        ExecutableElement method = TreeUtils.elementFromUse(node);
        if (method.getSimpleName().contentEquals("exit")
                && method.getEnclosingElement().getSimpleName().contentEquals("System"))
            alive = false;

        final String methodPackage = elements.getPackageOf(method).getQualifiedName().toString();
        boolean isJDKMethod = methodPackage.startsWith("java") || methodPackage.startsWith("com.sun");
        for (int i = 0; i < vars.size(); i++) {
            Element var = vars.get(i);
            for (AnnotationMirror a : annotations)
                if (!isJDKMethod && isNonFinalField(var) && !varDefHasAnnotation(a, var))
                    annos.clear(a, i);
        }


        List<? extends TypeMirror> thrown = method.getThrownTypes();
        if (!thrown.isEmpty()
                && TreeUtils.enclosingOfKind(getCurrentPath(), Tree.Kind.TRY) != null) {
            if (!tryBits.isEmpty())
                tryBits.peek().and(annos);
        }

        return null;
    }

    @Override
    public Void visitBlock(BlockTree node, Void p) {
        if (node.isStatic()) {
            GenKillBits<AnnotationMirror> prev = GenKillBits.copy(annos);
            try {
                super.visitBlock(node, p);
                return null;
            } finally {
                annos = prev;
            }
        }
        return super.visitBlock(node, p);
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        AnnotatedDeclaredType preMRT = visitorState.getMethodReceiver();
        MethodTree preMT = visitorState.getMethodTree();
        visitorState.setMethodReceiver(
                factory.getAnnotatedType(node).getReceiverType());
        visitorState.setMethodTree(node);

        // Intraprocedural, so save and restore bits.
        GenKillBits<AnnotationMirror> prev = GenKillBits.copy(annos);
        try {
            super.visitMethod(node, p);
            return null;
        } finally {
            annos = prev;
            visitorState.setMethodReceiver(preMRT);
            visitorState.setMethodTree(preMT);
        }
    }

    // **********************************************************************

    /**
     * Determines whether a variable definition has been annotated.
     *
     * @param annotation the annotation to check for
     * @param var the variable to check
     * @return true if the variable has the given annotation, false otherwise
     */
    private boolean varDefHasAnnotation(AnnotationMirror annotation, Element var) {

        if (annotatedVarDefs.containsKey(var))
            return annotatedVarDefs.get(var);

        boolean result = hasAnnotation(factory.getAnnotatedType(var), annotation);
        annotatedVarDefs.put(var, result);
        return result;
    }

    /**
     * Tests whether the element is of a non-final field
     *
     * @return true iff element is a non-final field
     */
    private static final boolean isNonFinalField(Element element) {
        return (element.getKind().isField()
                && !ElementUtils.isFinal(element));
    }
}