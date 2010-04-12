package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.actor.annotation.inittransactionalstate;
import se.scalablesolutions.akka.actor.annotation.transactionrequired;
import se.scalablesolutions.akka.persistence.common.*;
import se.scalablesolutions.akka.persistence.cassandra.*;

@transactionrequired
public class PersistentStatefulNested {
  private PersistentMap mapState;
  private PersistentVector vectorState;
  private PersistentRef refState;

  @inittransactionalstate
  public void init() {
    mapState = CassandraStorage.newMap();
    vectorState = CassandraStorage.newVector();
    refState = CassandraStorage.newRef();
  }

  public String getMapState(String key) {
    byte[] bytes = (byte[]) mapState.get(key.getBytes()).get();
    return new String(bytes, 0, bytes.length);
  }


  public String getVectorState(int index) {
    byte[] bytes = (byte[]) vectorState.get(index);
    return new String(bytes, 0, bytes.length);
  }

  public int getVectorLength() {
    return vectorState.length();
  }

  public String getRefState() {
    if (refState.isDefined()) {
      byte[] bytes = (byte[]) refState.get().get();
      return new String(bytes, 0, bytes.length);
    } else throw new IllegalStateException("No such element");
  }

  public void setMapState(String key, String msg) {
    mapState.put(key.getBytes(), msg.getBytes());
  }

  public void setVectorState(String msg) {
    vectorState.add(msg.getBytes());
  }

  public void setRefState(String msg) {
    refState.swap(msg.getBytes());
  }

  public String success(String key, String msg) {
    mapState.put(key.getBytes(), msg.getBytes());
    vectorState.add(msg.getBytes());
    refState.swap(msg.getBytes());
    return msg;
  }

  public String failure(String key, String msg, PersistentFailer failer) {
    mapState.put(key.getBytes(), msg.getBytes());
    vectorState.add(msg.getBytes());
    refState.swap(msg.getBytes());
    failer.fail();
    return msg;
  }
}
