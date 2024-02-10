/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.cluster.failover;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.Pair;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.FailoverEventListener;
import org.apache.activemq.artemis.api.core.client.FailoverEventType;
import org.apache.activemq.artemis.api.core.management.QueueControl;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorInternal;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.journal.impl.JournalFile;
import org.apache.activemq.artemis.core.journal.impl.JournalImpl;
import org.apache.activemq.artemis.core.paging.PagingStore;
import org.apache.activemq.artemis.core.persistence.impl.journal.DescribeJournal;
import org.apache.activemq.artemis.core.persistence.impl.journal.JournalStorageManager;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.files.FileMoveManager;
import org.apache.activemq.artemis.tests.integration.cluster.util.BackupSyncDelay;
import org.apache.activemq.artemis.tests.integration.cluster.util.TestableServer;
import org.apache.activemq.artemis.tests.util.TransportConfigurationUtils;
import org.apache.activemq.artemis.utils.ReusableLatch;
import org.apache.activemq.artemis.utils.UUID;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BackupSyncJournalTest extends FailoverTestBase {

   protected static final int BACKUP_WAIT_TIME = 60;
   private ServerLocatorInternal locator;
   protected ClientSessionFactoryInternal sessionFactory;
   protected ClientSession session;
   protected ClientProducer producer;
   private BackupSyncDelay syncDelay;
   private final int defaultNMsgs = 20;
   private int n_msgs = defaultNMsgs;

   protected void setNumberOfMessages(int nmsg) {
      this.n_msgs = nmsg;
   }

   protected int getNumberOfMessages() {
      return n_msgs;
   }

   protected final FailoverWaiter failoverWaiter = new FailoverWaiter();

   @Override
   @Before
   public void setUp() throws Exception {
      startBackupServer = false;
      super.setUp();
      setNumberOfMessages(defaultNMsgs);
      locator = (ServerLocatorInternal) getServerLocator().setBlockOnNonDurableSend(true).setBlockOnDurableSend(true).setReconnectAttempts(15).setRetryInterval(200);
      sessionFactory = createSessionFactoryAndWaitForTopology(locator, 1);
      sessionFactory.addFailoverListener(failoverWaiter);
      syncDelay = new BackupSyncDelay(backupServer, primaryServer);
   }

   @Test
   public void testNodeID() throws Exception {
      startBackupFinishSyncing();
      assertTrue("must be running", backupServer.isStarted());
      assertEquals("backup and primary should have the same nodeID", primaryServer.getServer().getNodeID(), backupServer.getServer().getNodeID());
   }

   @Test
   public void testReserveFileIdValuesOnBackup() throws Exception {
      final int totalRounds = 5;
      createProducerSendSomeMessages();
      JournalImpl messageJournal = getMessageJournalFromServer(primaryServer);
      for (int i = 0; i < totalRounds; i++) {
         messageJournal.forceMoveNextFile();
         sendMessages(session, producer, n_msgs);
      }
      Queue queue = primaryServer.getServer().locateQueue(ADDRESS);
      PagingStore store = queue.getPageSubscription().getPagingStore();

      // in case of paging I must close the current page otherwise we will get a pending counter
      // what would make the verification on similar journal to fail after the recovery
      if (store.isPaging()) {
         store.forceAnotherPage();
      }
      backupServer.start();

      // Deliver messages with Backup in-sync
      waitForRemoteBackup(sessionFactory, BACKUP_WAIT_TIME, false, backupServer.getServer());

      final JournalImpl backupMsgJournal = getMessageJournalFromServer(backupServer);
      sendMessages(session, producer, n_msgs);

      // in case of paging I must close the current page otherwise we will get a pending counter
      // what would make the verification on similar journal to fail after the recovery
      if (store.isPaging()) {
         store.forceAnotherPage();
      }

      // Deliver messages with Backup up-to-date
      syncDelay.deliverUpToDateMsg();
      waitForRemoteBackup(sessionFactory, BACKUP_WAIT_TIME, true, backupServer.getServer());
      // SEND more messages, now with the backup replicating
      sendMessages(session, producer, n_msgs);

      // in case of paging I must close the current page otherwise we will get a pending counter
      // what would make the verification on similar journal to fail after the recovery
      if (store.isPaging()) {
         store.forceAnotherPage();
      }

      Set<Pair<Long, Integer>> primaryIds = getFileIds(messageJournal);
      int size = messageJournal.getFileSize();
      PagingStore ps = primaryServer.getServer().getPagingManager().getPageStore(ADDRESS);
      if (ps.getPageSizeBytes() == PAGE_SIZE) {
         assertTrue("isStarted", ps.isStarted());
         assertFalse("start paging should return false, because we expect paging to be running", ps.startPaging());
      }
      finishSyncAndFailover();

      assertEquals("file sizes must be the same", size, backupMsgJournal.getFileSize());
      Set<Pair<Long, Integer>> backupIds = getFileIds(backupMsgJournal);

      int total = 0;
      for (Pair<Long, Integer> pair : primaryIds) {
         total += pair.getB();
      }
      int totalBackup = 0;
      for (Pair<Long, Integer> pair : backupIds) {
         totalBackup += pair.getB();
      }

      // "+ 2": there two other calls that send N_MSGS.
      for (int i = 0; i < totalRounds + 3; i++) {
         receiveMsgsInRange(0, n_msgs);
      }
      assertNoMoreMessages();
   }

   protected void assertNoMoreMessages() throws ActiveMQException {
      session.start();
      ClientConsumer consumer = session.createConsumer(ADDRESS);
      ClientMessage msg = consumer.receiveImmediate();
      assertNull("there should be no more messages to receive! " + msg, msg);
      consumer.close();
      session.commit();

   }

   protected void startBackupFinishSyncing() throws Exception {
      syncDelay.deliverUpToDateMsg();
      backupServer.start();
      waitForRemoteBackup(sessionFactory, BACKUP_WAIT_TIME, true, backupServer.getServer());
   }

   @Test
   public void testReplicationDuringSync() throws Exception {
      try {
         createProducerSendSomeMessages();
         backupServer.start();
         waitForRemoteBackup(sessionFactory, BACKUP_WAIT_TIME, false, backupServer.getServer());

         sendMessages(session, producer, n_msgs);
         session.commit();
         receiveMsgsInRange(0, n_msgs);

         finishSyncAndFailover();

         receiveMsgsInRange(0, n_msgs);
         assertNoMoreMessages();
      } catch (AssertionError error) {
         printJournal(primaryServer);
         printJournal(backupServer);
         // test failed
         throw error;
      }
   }

   void printJournal(TestableServer server) {
      try {
         System.out.println("\n\n BINDINGS JOURNAL\n\n");
         Configuration config = server.getServer().getConfiguration();
         DescribeJournal.describeBindingsJournal(config.getBindingsLocation());
         System.out.println("\n\n MESSAGES JOURNAL\n\n");
         DescribeJournal.describeMessagesJournal(config.getJournalLocation());
      } catch (Exception ignored) {
         ignored.printStackTrace();
      }
   }

   protected void finishSyncAndFailover() throws Exception {
      syncDelay.deliverUpToDateMsg();
      waitForRemoteBackup(sessionFactory, BACKUP_WAIT_TIME, true, backupServer.getServer());
      assertFalse("should not be initialized", backupServer.getServer().isActive());

      crash(session);
      assertTrue("backup initialized", backupServer.getServer().waitForActivation(5, TimeUnit.SECONDS));

      assertNodeIdWasSaved();
   }

   /**
    * @throws java.io.FileNotFoundException
    * @throws java.io.IOException
    * @throws InterruptedException
    */
   private void assertNodeIdWasSaved() throws Exception {
      assertTrue("backup initialized", backupServer.getServer().waitForActivation(5, TimeUnit.SECONDS));

      // assert that nodeID was saved (to the right file!)

      String journalDirectory = backupConfig.getJournalDirectory();

      File serverLockFile = new File(journalDirectory, "server.lock");
      assertTrue("server.lock must exist!\n " + serverLockFile, serverLockFile.exists());
      RandomAccessFile raFile = new RandomAccessFile(serverLockFile, "r");
      try {
         // verify the nodeID was written correctly
         FileChannel channel = raFile.getChannel();
         final int size = 16;
         ByteBuffer id = ByteBuffer.allocateDirect(size);
         int read = channel.read(id, 3);
         assertEquals("tried to read " + size + " bytes", size, read);
         byte[] bytes = new byte[16];
         id.position(0);
         id.get(bytes);
         UUID uuid = new UUID(UUID.TYPE_TIME_BASED, bytes);
         SimpleString storedNodeId = new SimpleString(uuid.toString());
         assertEquals("nodeId must match", backupServer.getServer().getNodeID(), storedNodeId);
      } finally {
         raFile.close();
      }
   }

   @Test
   public void testMessageSyncSimple() throws Exception {
      createProducerSendSomeMessages();
      startBackupCrashPrimary();
      receiveMsgsInRange(0, n_msgs);
      assertNoMoreMessages();
   }

   /**
    * Basic fail-back test.
    *
    * @throws Exception
    */
   @Test
   public void testFailBack() throws Exception {
      createProducerSendSomeMessages();
      startBackupCrashPrimary();
      receiveMsgsInRange(0, n_msgs);
      assertNoMoreMessages();

      sendMessages(session, producer, n_msgs);
      receiveMsgsInRange(0, n_msgs);
      assertNoMoreMessages();

      sendMessages(session, producer, 2 * n_msgs);
      assertFalse("must NOT be a backup", primaryServer.getServer().getHAPolicy().isBackup());
      adaptPrimaryConfigForReplicatedFailBack(primaryServer);
      FileMoveManager primaryMoveManager = new FileMoveManager(primaryServer.getServer().getConfiguration().getJournalLocation(), -1);
      primaryServer.getServer().lockActivation();
      try {
         primaryServer.start();
         assertTrue("must have become a backup", primaryServer.getServer().getHAPolicy().isBackup());
         Assert.assertEquals(0, primaryMoveManager.getNumberOfFolders());
      } finally {
         primaryServer.getServer().unlockActivation();
      }
      waitForServerToStart(primaryServer.getServer());
      primaryServer.getServer().waitForActivation(10, TimeUnit.SECONDS);
      Assert.assertEquals(1, primaryMoveManager.getNumberOfFolders());
      assertTrue("must be active now", !primaryServer.getServer().getHAPolicy().isBackup());

      assertTrue("Fail-back must initialize primary!", primaryServer.getServer().waitForActivation(15, TimeUnit.SECONDS));
      assertFalse("must be primary!", primaryServer.getServer().getHAPolicy().isBackup());
      int i = 0;
      while (!backupServer.isStarted() && i++ < 100) {
         Thread.sleep(100);
      }
      assertTrue(backupServer.getServer().isStarted());
      assertTrue(primaryServer.getServer().isStarted());
      receiveMsgsInRange(0, 2 * n_msgs);
      assertNoMoreMessages();
   }

   @Test
   public void testMessageSync() throws Exception {
      createProducerSendSomeMessages();
      receiveMsgsInRange(0, n_msgs / 2);
      startBackupCrashPrimary();
      receiveMsgsInRange(n_msgs / 2, n_msgs);
      assertNoMoreMessages();
   }

   @Test
   public void testRemoveAllMessageWithPurgeOnNoConsumers() throws Exception {
      final boolean purgeOnNoConsumers = true;
      createProducerSendSomeMessages();
      primaryServer.getServer().locateQueue(ADDRESS).setPurgeOnNoConsumers(purgeOnNoConsumers);
      assertEquals(n_msgs, ((QueueControl) primaryServer.getServer().getManagementService().getResource(ResourceNames.QUEUE + ADDRESS.toString())).removeAllMessages());
      startBackupCrashPrimary();
      assertNoMoreMessages();
   }

   @Test
   public void testReceiveAllMessagesWithPurgeOnNoConsumers() throws Exception {
      final boolean purgeOnNoConsumers = true;
      createProducerSendSomeMessages();
      primaryServer.getServer().locateQueue(ADDRESS).setPurgeOnNoConsumers(purgeOnNoConsumers);
      receiveMsgsInRange(0, n_msgs);
      startBackupCrashPrimary();
      assertNoMoreMessages();
   }

   private void startBackupCrashPrimary() throws Exception {
      assertFalse("backup is started?", backupServer.isStarted());
      primaryServer.removeInterceptor(syncDelay);
      backupServer.start();
      waitForBackup(sessionFactory, BACKUP_WAIT_TIME);
      failoverWaiter.reset();
      crash(session);
      backupServer.getServer().waitForActivation(5, TimeUnit.SECONDS);
      //for some system the retryAttempts and retryInterval may be too small
      //so that during failover all attempts have failed before the backup
      //server is fully activated.
      assertTrue("Session didn't failover, the maxRetryAttempts and retryInterval may be too small", failoverWaiter.waitFailoverComplete());
   }

   protected void createProducerSendSomeMessages() throws ActiveMQException {
      session = addClientSession(sessionFactory.createSession(true, true));
      session.createQueue(new QueueConfiguration(ADDRESS));
      if (producer != null)
         producer.close();
      producer = addClientProducer(session.createProducer(ADDRESS));
      sendMessages(session, producer, n_msgs);
      session.commit();
   }

   protected void receiveMsgsInRange(int start, int end) throws ActiveMQException {
      session.start();
      ClientConsumer consumer = addClientConsumer(session.createConsumer(ADDRESS));
      receiveMessages(consumer, start, end, true);
      consumer.close();
      session.commit();
   }

   private Set<Pair<Long, Integer>> getFileIds(JournalImpl journal) {
      Set<Pair<Long, Integer>> results = new HashSet<>();
      for (JournalFile jf : journal.getDataFiles()) {
         results.add(getPair(jf));
      }
      results.add(getPair(journal.getCurrentFile()));
      return results;
   }

   /**
    * @param jf
    * @return
    */
   private Pair<Long, Integer> getPair(JournalFile jf) {
      return new Pair<>(jf.getFileID(), jf.getPosCount());
   }

   static JournalImpl getMessageJournalFromServer(TestableServer server) {
      JournalStorageManager sm = (JournalStorageManager) server.getServer().getStorageManager();
      return (JournalImpl) sm.getMessageJournal();
   }

   @Override
   protected void createConfigs() throws Exception {
      createReplicatedConfigs();
   }

   @Override
   protected TransportConfiguration getAcceptorTransportConfiguration(boolean primary) {
      return TransportConfigurationUtils.getInVMAcceptor(primary);
   }

   @Override
   protected TransportConfiguration getConnectorTransportConfiguration(boolean primary) {
      return TransportConfigurationUtils.getInVMConnector(primary);
   }

   private class FailoverWaiter implements FailoverEventListener {

      private final ReusableLatch latch;

      FailoverWaiter() {
         latch = new ReusableLatch(1);
      }

      public void reset() {
         latch.setCount(0);
      }

      @Override
      public void failoverEvent(FailoverEventType eventType) {
         if (eventType == FailoverEventType.FAILOVER_COMPLETED) {
            latch.countDown();
         }
      }

      public boolean waitFailoverComplete() throws InterruptedException {
         return latch.await(10, TimeUnit.SECONDS);
      }
   }

}
