/**
 * Copyright 2010 - 2016 JetBrains s.r.o.
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
package jetbrains.exodus.bindings;

import jetbrains.exodus.ArrayByteIterable;
import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.util.LightOutputStream;
import jetbrains.exodus.util.StringInterner;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;

public class StringBinding extends ComparableBinding {

    public static final StringBinding BINDING;

    static {
        final String interner = System.getProperty("exodus.bindings.interner");
        if ("java".equalsIgnoreCase(interner)) {
            BINDING = new StringBindingWithJavaInterner();
        } else if ("xodus".equalsIgnoreCase(interner)) {
            BINDING = new StringBindingWithXodusInterner();
        } else {
            BINDING = new StringBinding();
        }
    }

    private StringBinding() {
    }

    @Override
    public String readObject(@NotNull final ByteArrayInputStream stream) {
        return BindingUtils.readString(stream);
    }

    @Override
    public void writeObject(@NotNull LightOutputStream output, @NotNull Comparable object) {
        output.writeString((String) object);
    }

    public static String entryToString(@NotNull final ByteIterable entry) {
        return (String) BINDING.entryToObject(entry);
    }

    public static ArrayByteIterable stringToEntry(@NotNull final String object) {
        return BINDING.objectToEntry(object);
    }

    private static class StringBindingWithXodusInterner extends StringBinding {

        @Override
        public String readObject(@NotNull ByteArrayInputStream stream) {
            return StringInterner.intern(super.readObject(stream));
        }
    }

    private static class StringBindingWithJavaInterner extends StringBinding {

        @Override
        public String readObject(@NotNull ByteArrayInputStream stream) {
            return super.readObject(stream).intern();
        }
    }
}
