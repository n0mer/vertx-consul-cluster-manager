package io.vertx.spi.cluster.consul.impl;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents simple entry of any of the cluster maps located within consul kv store.
 * <p>
 * Key of any of the cluster maps can ONLY AND ONLY be represented as a simple string. In order to support different type of keys (ie. integers, doubles, etc)
 * we serialize consul entry's value as the key + value. We build cluster map out of consul kv store map by deserializing a value which essentially is an entry of key and value.
 *
 * @param <K> actual type of key.
 * @param <V> actual type of value.
 * @author Roman Levytskyi
 * @See {@link ConversationUtils}
 */
final class ConsulEntry<K, V> implements Serializable {
  final private K key;
  final private V value;
  final private String nodeId; // nodeId given consul entry belongs to
  final private Optional<Long> ttl; // ttl on entry if present.

  ConsulEntry(K key, V value, String nodeId, Optional<Long> ttl) {
    this.key = key;
    this.value = value;
    this.nodeId = nodeId;
    this.ttl = ttl;
  }

  public K getKey() {
    return key;
  }

  public V getValue() {
    return value;
  }

  public String getNodeId() {
    return nodeId;
  }

  public Optional<Long> getTtl() {
    return ttl;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ConsulEntry<?, ?> that = (ConsulEntry<?, ?>) o;
    return Objects.equals(getKey(), that.getKey()) &&
      Objects.equals(getValue(), that.getValue()) &&
      Objects.equals(nodeId, that.nodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getKey(), getValue(), nodeId);
  }

  @Override
  public String toString() {
    return "ConsulEntry{" +
      "key=" + key +
      ", value=" + value +
      ", nodeId='" + nodeId + '\'' +
      '}';
  }
}
