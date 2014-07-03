/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.store.kv;

import com.addthis.codec.codables.BytesCodable;

public interface KeyValuePage<K, V extends BytesCodable> extends KeyValueStore<K, V> {

    /**
     * @return first key of next page or null if last page
     */
    public K getNextFirstKey();
}
