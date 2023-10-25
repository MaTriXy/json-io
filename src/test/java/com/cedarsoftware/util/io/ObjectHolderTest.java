package com.cedarsoftware.util.io;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Kai Hufenbach
 *
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
public class ObjectHolderTest
{
    @Test
    public void simpleTest()
    {
        ObjectHolder holder = new ObjectHolder("bool", true);
        String json = TestUtil.toJson(holder);
        ObjectHolder deserialized = TestUtil.toJava(json);
        assertEquals(holder, deserialized);
    }

    @Test
    public void testWithoutMetaData()
    {
        ObjectHolder boolHolder = new ObjectHolder("bool", true);
        ObjectHolder stringHolder = new ObjectHolder("string", "true");
        ObjectHolder intHolder = new ObjectHolder("int", 123l); //convenience for test

        // Arrays will be created as Object[] arrays, as Javascript allows non uniform arrays. In deserialization process this could be checked, too.

        testSerialization(boolHolder);
        testSerialization(stringHolder);
        testSerialization(intHolder);

    }

    private void testSerialization(ObjectHolder holder)
    {
        Map<String, Object> serialParams = new HashMap<String, Object>();
        serialParams.put(JsonWriter.TYPE, false);
        Map<String, Object> deSerialParams = new HashMap<String, Object>();
        deSerialParams.put(JsonReader.UNKNOWN_OBJECT, ObjectHolder.class.getName());

        String json = TestUtil.toJson(holder, serialParams);
        ObjectHolder deserialized = (ObjectHolder) TestUtil.toJava(json, deSerialParams);
        assertEquals(holder, deserialized);
    }
}
