/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm.local

/**
 * Java-friendly atomic blocks.
 * <p/>
 * Example usage (in Java):
 * <p/>
 * <pre>
 * import akka.stm.*;
 * import akka.stm.local.Atomic;
 *
 * final Ref<Integer> ref = new Ref<Integer>(0);
 *
 * new Atomic() {
 *     public Object atomically() {
 *         return ref.set(1);
 *     }
 * }.execute();
 *
 * // To configure transactions pass a TransactionFactory
 *
 * TransactionFactory txFactory = new TransactionFactoryBuilder()
 *     .setReadonly(true)
 *     .build();
 *
 * Integer value = new Atomic<Integer>(txFactory) {
 *     public Integer atomically() {
 *         return ref.get();
 *     }
 * }.execute();
 * </pre>
 */
abstract class Atomic[T](factory: TransactionFactory) {
  def this() = this(DefaultLocalTransactionFactory)
  def atomically: T
  def execute: T = atomic(factory)(atomically)
}
