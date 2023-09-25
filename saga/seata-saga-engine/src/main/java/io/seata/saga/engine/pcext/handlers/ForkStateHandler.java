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

package io.seata.saga.engine.pcext.handlers;

import io.seata.common.exception.FrameworkErrorCode;
import io.seata.common.util.CollectionUtils;
import io.seata.saga.engine.StateMachineConfig;
import io.seata.saga.engine.exception.EngineExecutionException;
import io.seata.saga.engine.pcext.StateHandler;
import io.seata.saga.engine.pcext.StateInstruction;
import io.seata.saga.engine.pcext.utils.ParallelContextHolder;
import io.seata.saga.engine.pcext.utils.ParallelTaskUtils;
import io.seata.saga.proctrl.ProcessContext;
import io.seata.saga.proctrl.eventing.impl.ProcessCtrlEventPublisher;
import io.seata.saga.statelang.domain.DomainConstants;
import io.seata.saga.statelang.domain.ForkState;
import io.seata.saga.statelang.domain.State;
import io.seata.saga.statelang.domain.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ForkState handler
 *
 * @author ptyin
 */
public class ForkStateHandler implements StateHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForkStateHandler.class);

    @Override
    public void process(ProcessContext context) throws EngineExecutionException {
        StateInstruction instruction = context.getInstruction(StateInstruction.class);
        ForkState forkState = (ForkState) instruction.getState(context);
        List<String> branches = forkState.getBranches();
        checkBranches(forkState, branches);

        StateMachineConfig stateMachineConfig =
                (StateMachineConfig) context.getVariable(DomainConstants.VAR_NAME_STATEMACHINE_CONFIG);
        ProcessCtrlEventPublisher publisher = stateMachineConfig.getAsyncProcessCtrlEventPublisher();
        if (!stateMachineConfig.isEnableAsync() || publisher == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous start is disabled. Parallel execution will run serially.");
            }
            // Use synchronized event publisher instead
            publisher = stateMachineConfig.getProcessCtrlEventPublisher();
        }

        publishBranches(publisher, context, forkState);
    }

    protected static void checkBranches(State forkState, List<String> branches) {
        if (CollectionUtils.isEmpty(branches)) {
            throw new EngineExecutionException(
                    "State [" + forkState.getName() + "] parallel branch should have at least one",
                    FrameworkErrorCode.ParameterRequired
            );
        }
        StateMachine stateMachine = forkState.getStateMachine();
        for (String branch: branches) {
            State branchState = stateMachine.getState(branch);
            if (branchState == null) {
                throw new EngineExecutionException(String.format("Fork state [%s] branch [%s] cannot be found",
                        forkState.getName(), branch), FrameworkErrorCode.ObjectNotExists);
            }
        }
    }

    /**
     * Publish branch context to event bus.
     *
     * @param publisher Event publisher
     * @param context   Current context
     * @param state     Current fork state
     */
    protected static void publishBranches(ProcessCtrlEventPublisher publisher,
                                          ProcessContext context, ForkState state) {
        List<String> branches = state.getBranches();
        int totalBranches = branches.size();
        int maxBranches = state.getParallel() == 0 ? totalBranches : Math.min(totalBranches, state.getParallel());
        StateMachine stateMachine = state.getStateMachine();

        ParallelContextHolder parallelContextHolder = ParallelContextHolder.getInstance(context, state);
        for (int i = 0; i < maxBranches; i++) {
            State branchState = stateMachine.getState(parallelContextHolder.next());
            if (branchState == null) {
                throw new EngineExecutionException(String.format("Fork state [%s] branch [%s] cannot be found",
                        state.getName(), branches.get(i)), FrameworkErrorCode.ObjectNotExists);
            }

            // Fork context
            ProcessContext childContext = ParallelTaskUtils.forkProcessContext(context, branchState);
            childContext.setVariable(DomainConstants.VAR_NAME_CURRENT_PARALLEL_CONTEXT_HOLDER, parallelContextHolder);
            // Publish it to async event bus
            publisher.publish(childContext);
        }
    }
}
