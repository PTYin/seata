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

package io.seata.saga.statelang.builder.impl;

import io.seata.common.util.StringUtils;
import io.seata.saga.statelang.builder.StatesConfigurer;
import io.seata.saga.statelang.builder.SubStateMachineBuilder;
import io.seata.saga.statelang.domain.ServiceTaskState;
import io.seata.saga.statelang.domain.SubStateMachine;
import io.seata.saga.statelang.domain.impl.SubStateMachineImpl;
import io.seata.saga.statelang.parser.impl.CompensateSubStateMachineStateParser;

/**
 * Default implementation for {@link SubStateMachineBuilder}
 *
 * @author ptyin
 */
public class SubStateMachineBuilderImpl
        extends AbstractTaskStateBuilder<SubStateMachineBuilder, SubStateMachine>
        implements SubStateMachineBuilder {

    protected SubStateMachineImpl state;

    @Override
    public SubStateMachineBuilder withStateMachineName(String stateMachineName) {
        state.setStateMachineName(stateMachineName);
        return this;
    }

    protected void beforeBuilt() {
        if (StringUtils.isBlank(state.getCompensateState())) {
            CompensateSubStateMachineStateParser compensateSubStateMachineStateParser
                    = new CompensateSubStateMachineStateParser();
            ServiceTaskState subStateMachineCompenState = compensateSubStateMachineStateParser.parse(null);
            state.setCompensateStateObject(subStateMachineCompenState);
            state.setCompensateState(subStateMachineCompenState.getName());
        }
    }

    @Override
    public StatesConfigurer and() {
        beforeBuilt();
        return super.and();
    }

    @Override
    public SubStateMachine build() {
        beforeBuilt();
        return super.build();
    }

    @Override
    protected SubStateMachineBuilder getPropertyBuilder() {
        return this;
    }

    @Override
    protected SubStateMachine getState() {
        if (state == null) {
            state = new SubStateMachineImpl();
        }
        return state;
    }
}
