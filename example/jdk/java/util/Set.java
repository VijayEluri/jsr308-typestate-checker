package java.util;

import static checkers.typestate.iteratorexample.IteratorStates.*;

public interface Set<E extends Object> extends java.util.Collection<E> {
  public abstract int size();
  public abstract boolean isEmpty();
  public abstract boolean contains(java.lang.Object a1);
  public abstract @CheckHasNext java.util.Iterator<E> iterator();
  public abstract java.lang.Object [] toArray();
  public abstract <T> T [] toArray(T[] a1);
  public abstract boolean add(E a1);
  public abstract boolean remove(java.lang.Object a1);
  public abstract boolean containsAll(java.util.Collection<?> a1);
  public abstract boolean addAll(java.util.Collection<? extends E> a1);
  public abstract boolean retainAll(java.util.Collection<?> a1);
  public abstract boolean removeAll(java.util.Collection<?> a1);
  public abstract void clear();
  public abstract boolean equals(java.lang.Object a1);
  public abstract int hashCode();
}
