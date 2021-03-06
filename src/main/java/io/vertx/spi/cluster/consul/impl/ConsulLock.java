package io.vertx.spi.cluster.consul.impl;

import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.Lock;
import io.vertx.ext.consul.KeyValueOptions;

/**
 * Consul-based implementation of an asynchronous exclusive lock which can be obtained from any node in the cluster.
 * When the lock is obtained (acquired), no-one else in the cluster can obtain the lock with the same name until the lock is released.
 * <p>
 * <b> Given implementation is based on using consul sessions - see https://www.consul.io/docs/guides/leader-election.html.</b>
 * Some notes:
 * <p>
 * The state of our lock would then correspond to the existence or non-existence of the respective key in the key-value store.
 * In order to obtain the lock we create a simple kv pair in Consul kv store and bound it with ttl session's id.
 * In order to release the lock we remove respective entry (we don't destroy respective ttl session which triggers automatically the deleting of kv pair that was bound to it).
 * <p>
 * Some additional details:
 * https://github.com/hashicorp/consul/issues/432
 * https://blog.emilienkenler.com/2017/05/20/distributed-locking-with-consul/
 * <p>
 * Note: given implementation doesn't require to serialize/deserialize lock related data, instead it just manipulates plain strings.
 *
 * @author Roman Levytskyi
 */
public class ConsulLock extends ConsulMap<String, String> implements Lock {

  private static final Logger log = LoggerFactory.getLogger(ConsulLock.class);

  private final String lockName;
  private final String sessionId;
  private final long timeout;

  /**
   * Creates an instance of consul based lock. MUST NOT be executed on the vertx event loop.
   *
   * @param name    - lock's name.
   * @param checkId - check id to which session id will get bound to.
   * @param timeout - time trying to obtain a lock in ms.
   * @param context - cluster manager mapContext.
   */
  public ConsulLock(String name, String checkId, long timeout, ConsulMapContext context) {
    super("__vertx.locks", context);
    this.lockName = name;
    this.timeout = timeout;
    this.sessionId = obtainSessionId(checkId);
  }


  /**
   * Tries to obtain a lock. MUST NOT be executed on the vertx event loop.
   *
   * @return true - lock has been successfully obtained, false - otherwise.
   */
  public boolean tryObtain() {
    log.trace("[" + mapContext.getNodeId() + "]" + " is trying to obtain a lock on: " + lockName);
    boolean lockObtained = completeAndGet(obtain(), timeout);
    if (lockObtained) {
      log.info("Lock on: " + lockName + " has been obtained.");
    }
    return lockObtained;
  }

  @Override
  public void release() {
    destroySession(sessionId).setHandler(event -> {
      if (event.succeeded()) log.info("Lock on: " + lockName + " has been released.");
      else
        throw new VertxException("Failed to release a lock on: " + lockName + ". Lock might have been already released.", event.cause());
    });
  }

  /**
   * Obtains a session id from consul. IMPORTANT : lock MUST be bound to tcp check since failed (failing) node must give up any locks being held by it,
   * therefore obtained session id is being already bounded to tcp check.
   *
   * @param checkId - tcp check id.
   * @return consul session id.
   */
  private String obtainSessionId(String checkId) {
    return completeAndGet(registerSession("Session for lock: " + lockName + " of: " + mapContext.getNodeId(), checkId), timeout);
  }

  /**
   * Obtains the lock asynchronously.
   */
  private Future<Boolean> obtain() {
    return putPlainValue(keyPath(lockName), "lockAcquired", new KeyValueOptions().setAcquireSession(sessionId));
  }
}
