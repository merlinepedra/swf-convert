/*
 * Copyright 2020 Nicolas Maltais
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maltaisn.swfconvert.app.params

import com.maltaisn.swfconvert.app.ConfigException


interface FormatParams<T> {

    val params: BaseParams

    /**
     * Validate and create the configurations associated to the parameters.
     *
     * @param count Number of input file collections. Any parameter related to the number
     * of input file collections should have the same number of values. This function
     * is also expected to return a list containing [count] configurations.
     *
     * @throws ConfigException Thrown on validation error.
     */
    fun createConfigurations(count: Int): List<T>

    /**
     * Print the values of the parameters to the standard output.
     */
    fun print()

}