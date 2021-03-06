package org.radargun.stressors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.CacheWrapper;
import org.radargun.features.AtomicOperationsCapable;
import org.radargun.stages.helpers.Range;
import org.radargun.state.SlaveState;
import org.radargun.utils.Utils;

/**
* Stressor thread running during many stages.
*
* @author Radim Vansa &lt;rvansa@redhat.com&gt;
* @since 1/3/13
*/
class BackgroundStressor extends Thread {

   private static final Log log = LogFactory.getLog(BackgroundStressor.class);
   private static final boolean trace = log.isTraceEnabled();

   private final int keyRangeStart;
   private final int keyRangeEnd;
   private final List<Range> deadSlavesRanges;
   private final BackgroundOpsManager manager;
   private final KeyGenerator keyGenerator;

   private final SynchronizedStatistics threadStats = new SynchronizedStatistics();
   private volatile boolean terminate = false;
   private int remainingTxOps;
   private boolean loaded;
   private final BackgroundStressor.Logic logic;
   private final int threadId;

   public BackgroundStressor(BackgroundOpsManager manager, SlaveState slaveState, Range myRange, List<Range> deadSlavesRanges, int slaveIndex, int idx) {
      super("StressorThread-" + idx);
      this.threadId = slaveIndex * manager.getNumThreads() + idx;
      this.manager = manager;
      this.keyRangeStart = myRange.getStart();
      this.keyRangeEnd = myRange.getEnd();
      this.deadSlavesRanges = deadSlavesRanges;
      this.keyGenerator = manager.getKeyGenerator();
      if (manager.isUseLogValues()) {
         if (manager.isSharedKeys()) {
            logic = new SharedLogLogic(threadId);
         } else {
            logic = new PrivateLogLogic(threadId);
         }
      } else {
         logic = new LegacyLogic(myRange.getStart());
      }
      this.remainingTxOps = manager.getTransactionSize();
   }

   private void loadData() {
      log.trace("Loading key range [" + keyRangeStart + ", " + keyRangeEnd + "]");
      loadKeyRange(keyRangeStart, keyRangeEnd);
      if (deadSlavesRanges != null) {
         for (Range range : deadSlavesRanges) {
            log.trace("Loading key range for dead slave: [" + range.getStart() + ", " + range.getEnd() + "]");
            loadKeyRange(range.getStart(), range.getEnd());
         }
      }
      loaded = true;
   }

   private void loadKeyRange(int from, int to) {
      int loaded_keys = 0;
      CacheWrapper cacheWrapper = manager.getCacheWrapper();
      boolean loadWithPutIfAbsent = manager.getLoadWithPutIfAbsent();
      AtomicOperationsCapable atomicWrapper = null;
      if (loadWithPutIfAbsent && !(cacheWrapper instanceof AtomicOperationsCapable)) {
         throw new IllegalArgumentException("This cache wrapper does not support atomic operations");
      } else {
         atomicWrapper = (AtomicOperationsCapable) cacheWrapper;
      }
      int entrySize = manager.getEntrySize();
      Random rand = new Random();
      for (long keyId = from; keyId < to && !terminate; keyId++, loaded_keys++) {
         while (!terminate) {
            try {
               Object key = keyGenerator.generateKey(keyId);
               if (loadWithPutIfAbsent) {
                  atomicWrapper.putIfAbsent(manager.getBucketId(), key, generateRandomEntry(rand, entrySize));
               } else {
                  cacheWrapper.put(manager.getBucketId(), key, generateRandomEntry(rand, entrySize));
               }
               if (loaded_keys % 1000 == 0) {
                  log.debug("Loaded " + loaded_keys + " out of " + (to - from));
               }
               // if we get an exception, it's OK - we can retry.
               break;
            } catch (Exception e) {
               log.error("Error while loading data", e);
            }
         }
      }
      log.debug("Loaded all " + (to - from) + " keys");
   }

   @Override
   public void run() {
      try {
         if (!loaded) {
            loadData();
         }
         if (manager.getLoadOnly()) {
            log.info("The stressor has finished loading data and will terminate.");
            return;
         }
         while (!isInterrupted() && !terminate) {
            logic.invoke();
            sleep(manager.getDelayBetweenRequests());
         }
      } catch (InterruptedException e) {
         log.trace("Stressor interrupted.");
         // we should close the transaction, otherwise TX Reaper would find dead thread in tx
         if (manager.getTransactionSize() > 0) {
            try {
               CacheWrapper cacheWrapper = manager.getCacheWrapper();
               if (cacheWrapper != null && cacheWrapper.isRunning()) {
                  cacheWrapper.endTransaction(false);
               }
            } catch (Exception e1) {
               log.error("Error while ending transaction", e);
            }
         }
      }
   }

   private InterruptedException findInterruptionCause(Throwable eParent, Throwable e) {
      if (e == null || eParent == e) {
         return null;
      } else if (e instanceof InterruptedException) {
         return (InterruptedException) e;
      } else {
         return findInterruptionCause(e, e.getCause());
      }
   }

   private byte[] generateRandomEntry(Random rand, int size) {
      // each char is 2 bytes
      byte[] data = new byte[size];
      rand.nextBytes(data);
      return data;
   }

   public boolean isLoaded() {
      return loaded;
   }

   public void setLoaded(boolean loaded) {
      this.loaded = loaded;
   }

   public void requestTerminate() {
      terminate = true;
   }

   public SynchronizedStatistics getStatsSnapshot(boolean reset, long time) {
      SynchronizedStatistics snapshot = threadStats.snapshot(reset,  time);
      return snapshot;
   }

   public String getStatus() {
      return String.format("%s [id=%d, terminated=%s]: %s [%s]", getName(), threadId, terminate,
            logic.getClass().getSimpleName(), logic.getStatus());
   }

   private interface Logic {
      public void invoke() throws InterruptedException;
      String getStatus();
   }

   private class LegacyLogic implements Logic {
      private final Random rand = new Random();
      private volatile long currentKey;

      public LegacyLogic(long startKey) {
         currentKey = startKey;
      }

      public void invoke() throws InterruptedException {
         long startTime = 0;
         Object key = null;
         Operation operation = manager.getOperation(rand);
         CacheWrapper cacheWrapper = manager.getCacheWrapper();
         try {
            key = keyGenerator.generateKey(currentKey++);
            if (currentKey == keyRangeEnd) {
               currentKey = keyRangeStart;
            }
            int transactionSize = manager.getTransactionSize();
            if (transactionSize > 0 && remainingTxOps == transactionSize) {
               cacheWrapper.startTransaction();
            }
            startTime = System.nanoTime();
            Object result;
            switch (operation)
            {
            case GET:
               result = cacheWrapper.get(manager.getBucketId(), key);
               if (result == null) operation = Operation.GET_NULL;
               break;
            case PUT:
               cacheWrapper.put(manager.getBucketId(), key, generateRandomEntry(rand, manager.getEntrySize()));
               break;
            case REMOVE:
               cacheWrapper.remove(manager.getBucketId(), key);
               break;
            }
            threadStats.registerRequest(System.nanoTime() - startTime, 0, operation);
            if (transactionSize > 0) {
               remainingTxOps--;
               if (remainingTxOps == 0) {
                  cacheWrapper.endTransaction(true);
                  remainingTxOps = transactionSize;
               }
            }
         } catch (Exception e) {
            InterruptedException ie = findInterruptionCause(null, e);
            if (ie != null) {
               throw ie;
            } else if (e.getClass().getName().contains("SuspectException")) {
               log.error("Request failed due to SuspectException: " + e.getMessage());
            } else {
               log.error("Cache operation error", e);
            }
            if (manager.getTransactionSize() > 0) {
               try {
                  cacheWrapper.endTransaction(false);
               } catch (Exception e1) {
                  log.error("Error while ending transaction", e);
               }
               remainingTxOps = manager.getTransactionSize();
            }
            threadStats.registerError(startTime <= 0 ? 0 : System.nanoTime() - startTime, 0, operation);
         }
      }

      @Override
      public String getStatus() {
         return String.format("currentKey=%s, remainingTxOps=%d", keyGenerator.generateKey(currentKey), remainingTxOps);
      }
   }

   private abstract class AbstractLogLogic<ValueType> implements Logic {

      protected final Random keySelectorRandom;
      protected final Random operationTypeRandom = new Random();
      protected final CacheWrapper cacheWrapper;
      protected final int transactionSize = manager.getTransactionSize();
      protected volatile long operationId = 0;
      protected volatile long keyId;
      protected Map<Long, DelayedRemove> delayedRemoves = new HashMap<Long, DelayedRemove>();
      private volatile long txStartOperationId;
      private volatile long txStartKeyId = -1;
      private long txStartRandSeed;
      private boolean txRolledBack = false;
      private volatile long lastSuccessfulOpTimestamp;
      private volatile long lastSuccessfulTxTimestamp;

      public AbstractLogLogic(long seed) {
         cacheWrapper = manager.getCacheWrapper();
         Random rand = null;
         try {
            Object last = cacheWrapper.get(manager.getBucketId(), LogChecker.lastOperationKey(threadId));
            if (last != null) {
               operationId = ((LogChecker.LastOperation) last).getOperationId() + 1;
               rand = Utils.setRandomSeed(new Random(0), ((LogChecker.LastOperation) last).getSeed());
               log.debug("Restarting operations from operation " + operationId);
            }
         } catch (Exception e) {
            log.error("Failure getting last operation", e);
         }
         if (rand == null) {
            log.trace("Initializing random with " + seed);
            this.keySelectorRandom = new Random(seed);
         } else {
            this.keySelectorRandom = rand;
         }
      }

      @Override
      public void invoke() throws InterruptedException {
         keyId = nextKeyId();
         do {
            if (txRolledBack) {
               keyId = txStartKeyId;
               operationId = txStartOperationId;
               Utils.setRandomSeed(keySelectorRandom, txStartRandSeed);
               txRolledBack = false;
            }
            if (trace) {
               log.trace("Operation " + operationId + " on key " + keyGenerator.generateKey(keyId));
            }
         } while (!invokeOn(keyId) && !isInterrupted() && !terminate);
         operationId++;
      }

      @Override
      public String getStatus() {
         long currentTime = System.currentTimeMillis();
         return String.format("current[id=%d, key=%s], lastSuccessfulOpTime=%d",
               operationId, keyGenerator.generateKey(keyId), lastSuccessfulOpTimestamp - currentTime)
               + (manager.getTransactionSize() > 0 ?
                  String.format(", txStart[id=%d, key=%s], remainingTxOps=%d, lastSuccessfulTxTime=%d",
                        txStartOperationId, keyGenerator.generateKey(txStartKeyId), remainingTxOps,
                        lastSuccessfulTxTimestamp - currentTime) : "");
      }

      protected abstract long nextKeyId();

      /* Return value = true: follow with next operation,
                       false: txRolledBack ? restart from txStartOperationId : retry operationId */
      protected boolean invokeOn(long keyId) throws InterruptedException {
         try {
            if (transactionSize > 0 && remainingTxOps == transactionSize) {
               txStartOperationId = operationId;
               txStartKeyId = keyId;
               // we could serialize & deserialize instead, but that's not much better
               txStartRandSeed = Utils.getRandomSeed(keySelectorRandom);
               cacheWrapper.startTransaction();
            }

            boolean txBreakRequest = false;
            try {
               if (!invokeLogic(keyId)) return false;
            } catch (BreakTxRequest request) {
               txBreakRequest = true;
            }
            lastSuccessfulOpTimestamp = System.currentTimeMillis();

            // for non-transactional caches write the stressor last operation anytime (once in a while)
            if (transactionSize <= 0 && operationId % manager.getLogCounterUpdatePeriod() == 0) {
               writeStressorLastOperation();
            }

            if (transactionSize > 0) {
               remainingTxOps--;
               if (remainingTxOps <= 0 || txBreakRequest) {
                  try {
                     cacheWrapper.endTransaction(true);
                     lastSuccessfulTxTimestamp = System.currentTimeMillis();
                  } catch (Exception e) {
                     log.trace("Transaction was rolled back, restarting from operation " + txStartOperationId);
                     txRolledBack = true;
                     afterRollback();
                     return false;
                  } finally {
                     remainingTxOps = transactionSize;
                  }
                  if (terminate) {
                     // If the thread was interrupted and cache is registered as Synchronization (not XAResource)
                     // commit phase may fail but no exception is thrown. Therefore, we should terminate immediatelly
                     // as we don't want to remove entries while the modifications have not been written.
                     log.info("Thread is about to terminate, not executing delayed removes");
                     return false;
                  }
                  afterCommit();
                  if (terminate) {
                     // the removes may have failed and we have not repeated them due to termination
                     log.info("Thread is about to terminate, not writing the last operation");
                     return false;
                  }
                  if (txBreakRequest) {
                     log.trace("Transaction was committed sooner, retrying operation " + operationId);
                     return false;
                  }

                  // for non-transactional caches write the stressor last operation only after the transaction
                  // has finished
                  try {
                     cacheWrapper.startTransaction();
                     writeStressorLastOperation();
                     cacheWrapper.endTransaction(true);
                  } catch (Exception e) {
                     log.error("Cannot write stressor last operation", e);
                  }
               }
            }
            return true;
         } catch (Exception e) {
            InterruptedException ie = findInterruptionCause(null, e);
            if (ie != null) {
               throw ie;
            } else if (e.getClass().getName().contains("SuspectException")) {
               log.error("Request failed due to SuspectException: " + e.getMessage());
            } else {
               log.error("Cache operation error", e);
            }
            if (manager.getTransactionSize() > 0) {
               try {
                  cacheWrapper.endTransaction(false);
                  log.info("Transaction rolled back");
               } catch (Exception e1) {
                  log.error("Error while rolling back transaction", e1);
               } finally {
                  log.info("Restarting from operation " + txStartOperationId);
                  remainingTxOps = manager.getTransactionSize();
                  txRolledBack = true;
                  afterRollback();
               }
            }
            return false; // on the same key
         }
      }

      private void afterRollback() {
         delayedRemoves.clear();
      }

      private void afterCommit() {
         boolean inTransaction = false;
         while (!terminate) {
            try {
               if (inTransaction) {
                  try {
                     cacheWrapper.endTransaction(false);
                  } catch (Exception e) {
                  }
               }
               cacheWrapper.startTransaction();
               inTransaction = true;
               for (DelayedRemove delayedRemove : delayedRemoves.values()) {
                  checkedRemoveValue(delayedRemove.bucketId, delayedRemove.keyId, delayedRemove.oldValue);
               }
               cacheWrapper.endTransaction(true);
               lastSuccessfulTxTimestamp = System.currentTimeMillis();
               inTransaction = false;
               delayedRemoves.clear();
               return;
            } catch (Exception e) {
               log.error("Error while executing delayed removes.", e);
            }
         }
      }

      protected void delayedRemoveValue(String bucketId, long keyId, ValueType prevValue) throws Exception {
         if (transactionSize <= 0) {
            checkedRemoveValue(bucketId, keyId, prevValue);
         } else {
            // if we moved around the key within one transaction multiple times we don't want to delete the complement
            delayedRemoves.remove(~keyId);
            delayedRemoves.put(keyId, new DelayedRemove(bucketId, keyId, prevValue));
         }
      }

      protected abstract boolean checkedRemoveValue(String bucketId, long keyId, ValueType oldValue) throws Exception;

      private void writeStressorLastOperation() {
         try {
            // we have to write down the keySelectorRandom as well in order to be able to continue work if this slave
            // is restarted
            cacheWrapper.put(manager.getBucketId(), LogChecker.lastOperationKey(threadId),
                  new LogChecker.LastOperation(operationId, Utils.getRandomSeed(keySelectorRandom)));
         } catch (Exception e) {
            log.error("Error writing stressor last operation", e);
         }
      }

      protected abstract boolean invokeLogic(long keyId) throws Exception;

      protected Map<Integer, Long> getCheckedOperations(long minOperationId) throws StressorException, BreakTxRequest {
         Map<Integer, Long> minIds = new HashMap<Integer, Long>();
         for (int thread = 0; thread < manager.getNumThreads() * manager.getNumSlaves(); ++thread) {
            minIds.put(thread, getCheckedOperation(thread, minOperationId));
         }
         return minIds;
      }

      protected long getCheckedOperation(int thread, long minOperationId) throws StressorException, BreakTxRequest {
         long minReadOperationId = Long.MAX_VALUE;
         for (int i = 0; i < manager.getNumSlaves(); ++i) {
            Object lastCheck;
            try {
               lastCheck = cacheWrapper.get(manager.getBucketId(), LogChecker.checkerKey(i, thread));
            } catch (Exception e) {
               log.error("Cannot read last checked operation id for slave " + i + " and thread " + thread, e);
               throw new StressorException(e);
            }
            long readOperationId = lastCheck == null ? Long.MIN_VALUE : ((LogChecker.LastOperation) lastCheck).getOperationId();
            if (readOperationId < minOperationId && manager.isIgnoreDeadCheckers() && !manager.isSlaveAlive(i)) {
               try {
                  Object ignored = cacheWrapper.get(manager.getBucketId(), LogChecker.ignoredKey(i, thread));
                  if (ignored == null || (Long) ignored < minOperationId) {
                     log.debug(String.format("Setting ignore operation for checker slave %d and stressor %d: %s -> %d (last check %s)",
                           i, thread, ignored, minOperationId, lastCheck));
                     cacheWrapper.put(manager.getBucketId(), LogChecker.ignoredKey(i, thread), minOperationId);
                     if (transactionSize > 0) {
                        throw new BreakTxRequest();
                     }
                  }
                  minReadOperationId = Math.min(minReadOperationId, minOperationId);
               } catch (BreakTxRequest request) {
                  throw request;
               } catch (Exception e) {
                  log.error("Cannot overwrite last checked operation id for slave " + i + " and thread " + thread, e);
                  throw new StressorException(e);
               }
            } else {
               minReadOperationId = Math.min(minReadOperationId, readOperationId);
            }
         }
         return minReadOperationId;
      }

      protected class DelayedRemove {
         public final String bucketId;
         public final long keyId;
         public final ValueType oldValue;

         protected DelayedRemove(String bucketId, long keyId, ValueType oldValue) {
            this.bucketId = bucketId;
            this.keyId = keyId;
            this.oldValue = oldValue;
         }
      }
   }

   private static class StressorException extends Exception {
      public StressorException(Throwable cause) {
         super(cause);
      }
   }

   /*
    * Not a "problem" exception, but signalizes that we need to commit current transaction
    * and retry the currently executed operation in a new transaction.
    */
   private static class BreakTxRequest extends Exception {
   }

   private class PrivateLogLogic extends AbstractLogLogic<PrivateLogValue> {

      private PrivateLogLogic(long seed) {
         super(seed);
      }

      @Override
      protected long nextKeyId() {
         return keySelectorRandom.nextInt(keyRangeEnd - keyRangeStart) + keyRangeStart;
      }

      @Override
      protected boolean invokeLogic(long keyId) throws Exception {
         Operation operation = manager.getOperation(operationTypeRandom);
         String bucketId = manager.getBucketId();

         // first we have to get the value
         PrivateLogValue prevValue = checkedGetValue(bucketId, keyId);
         // now for modify operations, execute it
         if (prevValue == null || operation == Operation.PUT) {
            PrivateLogValue nextValue;
            PrivateLogValue backupValue = null;
            if (prevValue != null) {
               nextValue = getNextValue(prevValue);
            } else {
               // the value may have been removed, look for backup
                backupValue = checkedGetValue(bucketId, ~keyId);
               if (backupValue == null) {
                  nextValue = new PrivateLogValue(threadId, operationId);
               } else {
                  nextValue = getNextValue(backupValue);
               }
            }
            if (nextValue == null) {
               return false;
            }
            checkedPutValue(bucketId, keyId, nextValue);
            if (backupValue != null) {
               delayedRemoveValue(bucketId, ~keyId, backupValue);
            }
         } else if (operation == Operation.REMOVE) {
            PrivateLogValue nextValue = getNextValue(prevValue);
            if (nextValue == null) {
               return false;
            }
            checkedPutValue(bucketId, ~keyId, nextValue);
            delayedRemoveValue(bucketId, keyId, prevValue);
         } else {
            // especially GETs are not allowed here, because these would break the deterministic order
            // - each operationId must be written somewhere
            throw new UnsupportedOperationException("Only PUT and REMOVE operations are allowed for this logic.");
         }
         return true;
      }

      private PrivateLogValue getNextValue(PrivateLogValue prevValue) throws InterruptedException, BreakTxRequest {
         if (prevValue.size() >= manager.getLogValueMaxSize()) {
            int checkedValues;
            // TODO some limit after which the stressor will terminate
            for (;;) {
               if (isInterrupted() || terminate) {
                  return null;
               }
               long minReadOperationId;
               try {
                  minReadOperationId = getCheckedOperation(threadId, prevValue.getOperationId(0));
               } catch (StressorException e) {
                  return null;
               }
               if (prevValue.getOperationId(0) <= minReadOperationId) {
                  for (checkedValues = 1; checkedValues < prevValue.size() && prevValue.getOperationId(checkedValues) <= minReadOperationId; ++checkedValues) {
                     log.trace(String.format("Discarding operation %d (minReadOperationId is %d)", prevValue.getOperationId(checkedValues), minReadOperationId));
                  }
                  break;
               } else {
                  try {
                     Thread.sleep(100);
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     return null;
                  }
               }
            }
            return prevValue.shift(checkedValues, operationId);
         } else {
            return prevValue.with(operationId);
         }
      }

      private PrivateLogValue checkedGetValue(String bucketId, long keyId) throws Exception {
         DelayedRemove removed = delayedRemoves.get(keyId);
         if (removed != null) {
            return null;
         }
         Object prevValue;
         long startTime = System.nanoTime();
         try {
            prevValue = cacheWrapper.get(bucketId, keyGenerator.generateKey(keyId));
         } catch (Exception e) {
            threadStats.registerError(System.nanoTime() - startTime, 0, Operation.GET);
            throw e;
         }
         long endTime = System.nanoTime();
         if (prevValue != null && !(prevValue instanceof PrivateLogValue)) {
            threadStats.registerError(endTime - startTime, 0, Operation.GET);
            log.error("Value is not an instance of PrivateLogValue: " + prevValue);
            throw new IllegalStateException();
         } else {
            threadStats.registerRequest(endTime - startTime, 0, prevValue == null ? Operation.GET_NULL : Operation.GET);
            return (PrivateLogValue) prevValue;
         }
      }

      @Override
      protected boolean checkedRemoveValue(String bucketId, long keyId, PrivateLogValue expectedValue) throws Exception {
         Object prevValue;
         long startTime = System.nanoTime();
         try {
            prevValue = cacheWrapper.remove(bucketId, keyGenerator.generateKey(keyId));
         } catch (Exception e) {
            threadStats.registerError(System.nanoTime() - startTime, 0, Operation.REMOVE);
            throw e;
         }
         long endTime = System.nanoTime();
         boolean successful = false;
         if (prevValue != null) {
            if (!(prevValue instanceof PrivateLogValue)) {
               log.error("Value is not an instance of PrivateLogValue: " + prevValue);
            } else if (!prevValue.equals(expectedValue)) {
               log.error("Value is not the expected one: expected=" + expectedValue + ", found=" + prevValue);
            } else {
               successful = true;
            }
         } else if (expectedValue == null) {
            successful = true;
         } else {
            log.error("Expected to remove " + expectedValue + " but found " + prevValue);
         }
         if (successful) {
            threadStats.registerRequest(endTime - startTime, 0, Operation.REMOVE);
            return true;
         } else {
            threadStats.registerError(endTime - startTime, 0, Operation.REMOVE);
            throw new IllegalStateException();
         }
      }

      private void checkedPutValue(String bucketId, long keyId, PrivateLogValue value) throws Exception {
         long startTime = System.nanoTime();
         try {
            cacheWrapper.put(bucketId, keyGenerator.generateKey(keyId), value);
         } catch (Exception e) {
            threadStats.registerError(System.nanoTime() - startTime, 0, Operation.PUT);
            throw e;
         }
         long endTime = System.nanoTime();
         threadStats.registerRequest(endTime - startTime, 0, Operation.PUT);
      }

   }

   private class SharedLogLogic extends AbstractLogLogic<SharedLogValue> {
      private AtomicOperationsCapable atomicWrapper;

      public SharedLogLogic(long seed) {
         super(seed);
         atomicWrapper = (AtomicOperationsCapable) cacheWrapper;
      }

      @Override
      protected long nextKeyId() {
         return keySelectorRandom.nextInt(manager.getNumEntries());
      }

      @Override
      protected boolean invokeLogic(long keyId) throws Exception {
         Operation operation = manager.getOperation(operationTypeRandom);
         String bucketId = manager.getBucketId();

         // In shared mode, we can't ever atomically modify the two keys (main and backup) to have only
         // one of them with the actual value (this is not true even for private mode but there the moment
         // when we move the value from main to backup or vice versa does not cause any problem, because the
         // worst thing is to read slightly outdated value). However, here the invariant holds that the operation
         // must be recorded in at least one of the entries, but the situation with both of these having
         // some value is valid (although, we try to evade it be conditionally removing one of them in each
         // logic step).
         SharedLogValue prevValue, backupValue, nextValue;
         do {
            prevValue = checkedGetValue(bucketId, keyId);
            backupValue = checkedGetValue(bucketId, ~keyId);
            nextValue = getNextValue(prevValue, backupValue);
            if (terminate || Thread.currentThread().isInterrupted()) return false;
         } while (nextValue == null);
         // now for modify operations, execute it
         if (operation == Operation.PUT) {
            if (checkedPutValue(bucketId, keyId, prevValue, nextValue)) {
               if (backupValue != null) {
                  delayedRemoveValue(bucketId, ~keyId, backupValue);
               }
            } else {
               return false;
            }
         } else if (operation == Operation.REMOVE) {
            if (checkedPutValue(bucketId, ~keyId, backupValue, nextValue)) {
               if (prevValue != null) {
                  delayedRemoveValue(bucketId, keyId, prevValue);
               }
            } else {
               return false;
            }
         } else {
            // especially GETs are not allowed here, because these would break the deterministic order
            // - each operationId must be written somewhere
            throw new UnsupportedOperationException("Only PUT and REMOVE operations are allowed for this logic.");
         }
         return true;
      }

      private SharedLogValue getNextValue(SharedLogValue prevValue, SharedLogValue backupValue) throws StressorException, BreakTxRequest {
         if (prevValue == null && backupValue == null) {
            return new SharedLogValue(threadId, operationId);
         } else if (prevValue != null && backupValue != null) {
            SharedLogValue joinValue = prevValue.join(backupValue);
            if (joinValue.size() >= manager.getLogValueMaxSize()) {
               return filterAndAddOperation(joinValue);
            } else {
               return joinValue.with(threadId, operationId);
            }
         }
         SharedLogValue value = prevValue != null ? prevValue : backupValue;
         if (value.size() < manager.getLogValueMaxSize()) {
            return value.with(threadId, operationId);
         } else {
            return filterAndAddOperation(value);
         }
      }

      private SharedLogValue filterAndAddOperation(SharedLogValue value) throws StressorException, BreakTxRequest {
         Map<Integer, Long> operationIds = getCheckedOperations(value.minFrom(threadId));
         SharedLogValue filtered = value.with(threadId, operationId, operationIds);
         if (filtered.size() > manager.getLogValueMaxSize()) {
            return null;
         } else {
            return filtered;
         }
      }

      private SharedLogValue checkedGetValue(String bucketId, long keyId) throws Exception {
         Object prevValue;
         long startTime = System.nanoTime();
         try {
            prevValue = cacheWrapper.get(bucketId, keyGenerator.generateKey(keyId));
         } catch (Exception e) {
            threadStats.registerError(System.nanoTime() - startTime, 0, Operation.GET);
            throw e;
         }
         long endTime = System.nanoTime();
         if (prevValue != null && !(prevValue instanceof SharedLogValue)) {
            threadStats.registerError(endTime - startTime, 0, Operation.GET);
            log.error("Value is not an instance of SharedLogValue: " + prevValue);
            throw new IllegalStateException();
         } else {
            threadStats.registerRequest(endTime - startTime, 0, prevValue == null ? Operation.GET_NULL : Operation.GET);
            return (SharedLogValue) prevValue;
         }
      }

      private boolean checkedPutValue(String bucketId, long keyId, SharedLogValue oldValue, SharedLogValue newValue) throws Exception {
         boolean returnValue;
         long startTime = System.nanoTime();
         try {
            if (oldValue == null) {
               returnValue = atomicWrapper.putIfAbsent(bucketId, keyGenerator.generateKey(keyId), newValue) == null;
            } else {
               returnValue = atomicWrapper.replace(bucketId, keyGenerator.generateKey(keyId), oldValue, newValue);
            }
         } catch (Exception e) {
            threadStats.registerError(System.nanoTime() - startTime, 0, Operation.PUT);
            throw e;
         }
         long endTime = System.nanoTime();
         threadStats.registerRequest(endTime - startTime, 0, Operation.PUT);
         return returnValue;
      }

      @Override
      protected boolean checkedRemoveValue(String bucketId, long keyId, SharedLogValue oldValue) throws Exception {
         long startTime = System.nanoTime();
         try {
            boolean returnValue = atomicWrapper.remove(bucketId, keyGenerator.generateKey(keyId), oldValue);
            long endTime = System.nanoTime();
            threadStats.registerRequest(endTime - startTime, 0, Operation.REMOVE);
            return returnValue;
         } catch (Exception e) {
            threadStats.registerError(System.nanoTime() - startTime, 0, Operation.REMOVE);
            throw e;
         }
      }
   }
}
