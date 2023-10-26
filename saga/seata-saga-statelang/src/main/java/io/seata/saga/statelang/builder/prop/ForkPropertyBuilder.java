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

package io.seata.saga.statelang.builder.prop;

import java.util.Collection;

/**
 * Fork property builder
 *
 * @param <P> property builder type
 * @author ptyin
 */
public interface ForkPropertyBuilder<P extends ForkPropertyBuilder<P>> {
    /**
     * Configure parallel branches
     *
     * @param branches names of each first state in branch
     * @return builder for chaining
     */
    P withBranches(Collection<String> branches);

    /**
     * Configure max parallelism, default 0 which stands for no limit.
     *
     * @param parallel max parallelism, i.e. max count of threads at a specific moment
     * @return loop builder for chaining
     */
    P withParallel(int parallel);

    /**
     * Configure max await timeout, default 12 hours.
     *
     * @param timeout await timeout
     * @return builder for chaining
     */
    P withTimeout(long timeout);
}
