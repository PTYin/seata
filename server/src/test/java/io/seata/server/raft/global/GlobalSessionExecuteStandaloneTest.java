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

package io.seata.server.raft.global;

import io.seata.common.util.NetUtil;
import io.seata.server.cluster.raft.execute.global.AddGlobalSessionExecute;
import io.seata.server.cluster.raft.sync.msg.RaftGlobalSessionSyncMsg;
import io.seata.server.cluster.raft.sync.msg.dto.GlobalTransactionDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GlobalSessionExecuteStandaloneTest {

    static {
        System.setProperty("store.lock.mode", "raft");
        System.setProperty("store.session.mode", "raft");
        System.setProperty("server.raft.serverAddr", NetUtil.getLocalIp() + ":9091");
    }

    @Test
    public void testAdd() throws Throwable {
        RaftGlobalSessionSyncMsg sessionMsg = new RaftGlobalSessionSyncMsg();
        GlobalTransactionDTO mockDto = new GlobalTransactionDTO("123:123");
        mockDto.setApplicationData("hello, world");
        mockDto.setTransactionServiceGroup("test");
        mockDto.setTransactionName("test");
        mockDto.setTransactionId(123);
        mockDto.setTimeout(20);
        mockDto.setBeginTime(System.currentTimeMillis());
        mockDto.setApplicationId("test");
        mockDto.setStatus(1);

        sessionMsg.setGlobalSession(mockDto);
        AddGlobalSessionExecute execute = new AddGlobalSessionExecute();
        boolean success = execute.execute(sessionMsg);
        Assertions.assertTrue(success, "Add global transaction successfully");
    }
}