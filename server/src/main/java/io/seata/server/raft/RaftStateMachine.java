/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.raft;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import io.seata.common.XID;
import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.exception.TransactionException;
import io.seata.core.protocol.LeaderNotifyRequest;
import io.seata.core.rpc.netty.NettyRemotingServer;
import io.seata.core.store.StoreMode;
import io.seata.serializer.kryo.KryoSerializerFactory;
import io.seata.server.coordinator.DefaultCoordinator;
import io.seata.server.lock.LockerManagerFactory;
import io.seata.server.raft.execute.RaftMsgExecute;
import io.seata.server.raft.execute.branch.AddBranchSessionExecute;
import io.seata.server.raft.execute.branch.RemoveBranchSessionExecute;
import io.seata.server.raft.execute.branch.UpdateBranchSessionExecute;
import io.seata.server.raft.execute.global.AddGlobalSessionExecute;
import io.seata.server.raft.execute.global.RemoveGlobalSessionExecute;
import io.seata.server.raft.execute.global.UpdateGlobalSessionExecute;
import io.seata.server.raft.execute.lock.AcquireLockExecute;
import io.seata.server.raft.execute.lock.BranchReleaseLockExecute;
import io.seata.server.raft.execute.lock.GlobalReleaseLockExecute;
import io.seata.server.raft.msg.RaftSyncMsgSerializer;
import io.seata.server.raft.snapshot.RaftSnapshot;
import io.seata.server.raft.snapshot.RaftSnapshotFile;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHolder;
import io.seata.server.storage.raft.RaftSessionSyncMsg;
import io.seata.server.storage.raft.lock.RaftLockManager;
import io.seata.server.storage.raft.session.RaftSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.seata.server.session.SessionHolder.ROOT_SESSION_MANAGER_NAME;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.ACQUIRE_LOCK;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.ADD_BRANCH_SESSION;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.ADD_GLOBAL_SESSION;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.RELEASE_BRANCH_SESSION_LOCK;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.RELEASE_GLOBAL_SESSION_LOCK;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.REMOVE_BRANCH_SESSION;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.REMOVE_GLOBAL_SESSION;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.UPDATE_BRANCH_SESSION_STATUS;
import static io.seata.server.storage.raft.RaftSessionSyncMsg.MsgType.UPDATE_GLOBAL_SESSION_STATUS;

/**
 * @author funkye
 */
public class RaftStateMachine extends StateMachineAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaftStateMachine.class);

    private String mode;

    private static final KryoSerializerFactory FACTORY = KryoSerializerFactory.getInstance();

    private static final String BRANCH_SESSION_MAP = "branchSessionMap";

    private static final Map<MsgType, RaftMsgExecute> EXECUTES = new HashMap<>();

    /**
     * Leader term
     */
    private final AtomicLong leaderTerm = new AtomicLong(-1);

    public boolean isLeader() {
        return this.leaderTerm.get() > 0;
    }

    public RaftStateMachine() {
        mode = ConfigurationFactory.getInstance().getConfig(ConfigurationKeys.STORE_MODE);
        EXECUTES.put(ADD_GLOBAL_SESSION, new AddGlobalSessionExecute());
        EXECUTES.put(ACQUIRE_LOCK, new AcquireLockExecute());
        EXECUTES.put(ADD_BRANCH_SESSION, new AddBranchSessionExecute());
        EXECUTES.put(REMOVE_BRANCH_SESSION, new RemoveBranchSessionExecute());
        EXECUTES.put(UPDATE_GLOBAL_SESSION_STATUS, new UpdateGlobalSessionExecute());
        EXECUTES.put(RELEASE_GLOBAL_SESSION_LOCK, new GlobalReleaseLockExecute());
        EXECUTES.put(REMOVE_GLOBAL_SESSION, new RemoveGlobalSessionExecute());
        EXECUTES.put(UPDATE_BRANCH_SESSION_STATUS, new UpdateBranchSessionExecute());
        EXECUTES.put(RELEASE_BRANCH_SESSION_LOCK, new BranchReleaseLockExecute());
    }

    @Override
    public void onApply(Iterator iterator) {
        while (iterator.hasNext()) {
            if (iterator.done() != null) {
                // leader does not need to be serialized, just execute the task directly
                Optional.ofNullable(iterator.done()).ifPresent(done -> done.run(Status.OK()));
            } else {
                ByteBuffer byteBuffer = iterator.getData();
                // if data is empty, it is only a heartbeat event and can be ignored
                if (byteBuffer != null && byteBuffer.hasRemaining()) {
                    RaftSessionSyncMsg msg =
                        (RaftSessionSyncMsg)RaftSyncMsgSerializer.decode(byteBuffer.array()).getBody();
                    // follower executes the corresponding task
                    onExecuteRaft(msg);
                }
            }
            iterator.next();
        }
    }

    @Override
    public void onSnapshotSave(final SnapshotWriter writer, final Closure done) {
        if (!StringUtils.equals(StoreMode.RAFT.getName(), mode)) {
            return;
        }
        // gets a record of the lock and session at the moment
        Map<String, Object> maps = new HashMap<>(2);
        RaftSessionManager raftSessionManager = (RaftSessionManager)SessionHolder.getRootSessionManager();
        Map<String, GlobalSession> sessionMap = raftSessionManager.getSessionMap();
        Integer initialCapacity = sessionMap.size();
        Map<String, byte[]> globalSessionByteMap = new HashMap<>(initialCapacity);
        // each transaction is expected to have two branches
        Map<Long, byte[]> branchSessionByteMap = new HashMap<>(initialCapacity * 2);
        sessionMap.forEach((k, v) -> {
            globalSessionByteMap.put(v.getXid(), v.encode());
            List<BranchSession> branchSessions = v.getBranchSessions();
            branchSessions.forEach(
                branchSession -> branchSessionByteMap.put(branchSession.getBranchId(), branchSession.encode()));
        });
        maps.put(ROOT_SESSION_MANAGER_NAME, globalSessionByteMap);
        maps.put(BRANCH_SESSION_MAP, branchSessionByteMap);
        RaftSnapshot raftSnapshot = new RaftSnapshot();
        raftSnapshot.setBody(maps);
        LOGGER.info("globalSessionMap size :{}, branchSessionMap map size: {}", globalSessionByteMap.size(),
            branchSessionByteMap.size());
        String path = new StringBuilder(writer.getPath()).append(File.separator).append("data").toString();
        if (RaftSnapshotFile.save(raftSnapshot, path)) {
            if (writer.addFile("data")) {
                done.run(Status.OK());
            } else {
                done.run(new Status(RaftError.EIO, "Fail to add file to writer"));
            }
        } else {
            done.run(new Status(RaftError.EIO, "Fail to save counter snapshot %s", path));
        }
    }

    @Override
    public boolean onSnapshotLoad(final SnapshotReader reader) {
        if (!StringUtils.equals(StoreMode.RAFT.getName(), mode)) {
            return true;
        }
        if (isLeader()) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Leader is not supposed to load snapshot");
            }
            return false;
        }
        if (reader.getFileMeta("data") == null) {
            LOGGER.error("Fail to find data file in {}", reader.getPath());
            return false;
        }
        String path = new StringBuilder(reader.getPath()).append(File.separator).append("data").toString();
        try {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("on snapshot load start index: {}", reader.load().getLastIncludedIndex());
            }
            Map<String, Object> maps = RaftSnapshotFile.load(path);
            RaftSessionManager raftSessionManager = (RaftSessionManager)SessionHolder.getRootSessionManager();
            Map<String, byte[]> globalSessionByteMap = (Map<String, byte[]>)maps.get(ROOT_SESSION_MANAGER_NAME);
            Map<Long, byte[]> branchSessionByteMap = (Map<Long, byte[]>)maps.get(BRANCH_SESSION_MAP);
            Map<String, GlobalSession> rootSessionMap = raftSessionManager.getSessionMap();
            // be sure to clear the data before loading it, because this is a full overwrite update
            LockerManagerFactory.getLockManager().cleanAllLocks();
            rootSessionMap.clear();
            if (!globalSessionByteMap.isEmpty()) {
                Map<String, GlobalSession> sessionMap = new HashMap<>();
                globalSessionByteMap.forEach((k, v) -> {
                    GlobalSession session = new GlobalSession();
                    session.decode(v);
                    sessionMap.put(k, session);
                });
                rootSessionMap.putAll(sessionMap);
                if (CollectionUtils.isNotEmpty(branchSessionByteMap)) {
                    RaftLockManager fileLockManager = (RaftLockManager) LockerManagerFactory.getLockManager();
                    branchSessionByteMap.forEach((k, v) -> {
                        BranchSession branchSession = new BranchSession();
                        branchSession.decode(v);
                        try {
                            fileLockManager.localAcquireLock(branchSession);
                            rootSessionMap.get(branchSession.getXid()).add(branchSession);
                        } catch (TransactionException e) {
                            LOGGER.error(e.getMessage());
                        }
                    });
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("on snapshot load end index: {}", reader.load().getLastIncludedIndex());
            }
            return true;
        } catch (final Exception e) {
            LOGGER.error("fail to load snapshot from {}", path);
            return false;
        }

    }

    @Override
    public void onLeaderStart(final long term) {
        // become the leader again,reloading global session
        if (!isLeader() && RaftServerFactory.getInstance().isRaftMode()) {
            LOGGER.info("session map: {} ",SessionHolder.getRootSessionManager().allSessions().size());
            SessionHolder.reload(SessionHolder.getRootSessionManager().allSessions(), StoreMode.RAFT, false);
            NettyRemotingServer nettyRemotingServer =
                (NettyRemotingServer)DefaultCoordinator.getInstance().getRemotingServer();
            LeaderNotifyRequest leaderNotifyRequest = new LeaderNotifyRequest();
            leaderNotifyRequest.setAddress(XID.getIpAddressAndPort());
            nettyRemotingServer.sendSyncRequestAll(leaderNotifyRequest);
        }
        this.leaderTerm.set(term);
        super.onLeaderStart(term);
        DefaultCoordinator.getInstance().setPrevent(true);
    }

    @Override
    public void onLeaderStop(final Status status) {
        this.leaderTerm.set(-1);
        super.onLeaderStop(status);
        DefaultCoordinator.getInstance().setPrevent(false);
    }

    @Override
    public void onStopFollowing(final LeaderChangeContext ctx) {
        super.onStopFollowing(ctx);
    }

    @Override
    public void onStartFollowing(final LeaderChangeContext ctx) {
        super.onStartFollowing(ctx);
        DefaultCoordinator.getInstance().setPrevent(false);
    }

    private void onExecuteRaft(RaftSessionSyncMsg msg) {
        RaftMsgExecute execute = EXECUTES.get(msg.getMsgType());
        if (execute == null) {
            throw new RuntimeException(
                "the state machine does not allow events that cannot be executed, please feedback the information to the Seata community !!! msg: "
                    + msg);
        }
        try {
            execute.execute(msg);
        } catch (Throwable e) {
            LOGGER.error("Message synchronization failure: {}, msgType: {}", e.getMessage(), msg.getMsgType(), e);
            throw new RuntimeException(e);
        }
    }
}
