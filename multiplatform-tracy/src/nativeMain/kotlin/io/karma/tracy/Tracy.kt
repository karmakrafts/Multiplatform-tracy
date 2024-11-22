/**
 * Copyright 2024 Karma Krafts & associates
 *
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

package io.karma.tracy

import kotlinx.cinterop.*
import tracy.*

/**
 * @author Cedric Hammes
 * @since  19/11/2024
 */
object Tracy {
    /**
     * This function fills the specified parameters of the zone into a source location data and creates a Tracy zone out of the data. It is
     * the central part of measuring the application's performance.
     *
     * @param name     The name of the zone (required)
     * @param file     The file where the zone is being opened
     * @param color    The color of the zone in the tracer (white by default)
     * @param line     The line of the zone starting (zero by default)
     * @param function The name of the function beginning the zone
     * @return         Context referencing to the zone data
     *
     * @author Cedric Hammes
     * @since  19/11/2024
     */
    fun beginZone(name: String = "", file: String = "", function: String = "", line: UInt = 0U, color: UInt = 0xFFFFFFU): Zone {
        val sourceLocationData = nativeHeap.alloc<___tracy_source_location_data>().also { sourceLocationData ->
            if (name.isEmpty()) throw IllegalArgumentException("Zone name cannot be null!")
            sourceLocationData.line = line
            sourceLocationData.color = color
            name.encodeToByteArray().usePinned { pinned ->
                sourceLocationData.name = pinned.addressOf(0)
            }
            
            if (file.isNotEmpty()) {
                file.encodeToByteArray().usePinned { pinned ->
                    sourceLocationData.file = pinned.addressOf(0)
                }
            }
            if (function.isNotEmpty()) {
                function.encodeToByteArray().usePinned { pinned ->
                    sourceLocationData.function = pinned.addressOf(0)
                }
            }
        }
        
        return Zone(sourceLocationData)
    }
    
    /**
     * This function sends the specified message to the profiler. By default, the color is white but the color can be set by the user to a
     * custom color.
     *
     * @param message The message sent to the profiler
     * @param color   The color of the message
     *
     * @author Cedric Hammes
     * @since  19/11/2024
     */
    fun emitMessage(message: String, color: UInt = 0xFFFFFFU) = ___tracy_emit_messageC(message, message.length.toULong(), color, 0)
    
    /**
     * @author Cedric Hammes
     * @since  19/11/2024
     */
    fun emitFrameMark(mark: String): Unit = mark.encodeToByteArray().usePinned { pinned ->
        mmp_tracy_emit_frame_mark(pinned.addressOf(0))
    }
    
    /**
     * This class is an API referencing to the zone created with the Tracy API. It allows the developer to close/end the zone or mutate some
     * properties of the zone.
     *
     * @param data Structure containing meta-information about the zone being created
     *
     * @author Cedric Hammes
     * @since  19/11/2024
     */
    class Zone internal constructor(private val data: ___tracy_source_location_data) : AutoCloseable {
        private val contextValue: CValue<___tracy_c_zone_context> = ___tracy_emit_zone_begin(data.ptr, 1)
        
        // TODO: Add setter for name etc.
        
        override fun close() {
            ___tracy_emit_zone_end(contextValue)
            nativeHeap.free(data)
        }
    }
    
}
