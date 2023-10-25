package com.cedarsoftware.util.io

import com.google.gson.Gson
import groovy.transform.CompileStatic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * @author John DeRegnaucourt (jdereg@gmail.com)
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
@CompileStatic
class TestRoots
{
    @Test
    void testStringRoot()
    {
        Gson gson = new Gson()
        String g = gson.toJson("root should not be a string")
        String j = TestUtil.toJson("root should not be a string")
        assertEquals(g, j)
    }

    @Test
    void testRoots()
    {
        // Test Object[] as root element passed in
        Object[] foo = [new TestObject("alpha"), new TestObject("beta")] as Object[];

        String jsonOut = TestUtil.toJson(foo)
        TestUtil.printLine(jsonOut)

        Object[] bar = (Object[]) TestUtil.toJava(jsonOut)
        assertTrue(bar.length == 2)
        assertTrue(bar[0].equals(new TestObject("alpha")))
        assertTrue(bar[1].equals(new TestObject("beta")))

        String json = '["getStartupInfo",["890.022905.16112006.00024.0067ur","machine info"]]'
        Object[] baz = (Object[]) TestUtil.toJava(json)
        assertTrue(baz.length == 2)
        assertTrue("getStartupInfo".equals(baz[0]))
        Object[] args = (Object[]) baz[1];
        assertTrue(args.length == 2)
        assertTrue("890.022905.16112006.00024.0067ur".equals(args[0]))
        assertTrue("machine info".equals(args[1]))

        String hw = '["Hello, World"]'
        Object[] qux = (Object[]) TestUtil.toJava(hw)
        assertTrue(qux != null)
        assertTrue("Hello, World".equals(qux[0]))

        // Whitespace
        String pkg = TestObject.class.name
        Object[] fred = (Object[]) TestUtil.toJava('[  {  "@type"  :  "' + pkg + '"  ,  "_name"  :  "alpha"  ,  "_other"  :  null  }  ,  {  "@type"  :  "' + pkg + '"  ,  "_name"  :  "beta"  ,  "_other" : null  }  ]  ')
        assertTrue(fred != null)
        assertTrue(fred.length == 2)
        assertTrue(fred[0] == (new TestObject("alpha")))
        assertTrue(fred[1] == (new TestObject("beta")))

        Object[] wilma = (Object[]) TestUtil.toJava('[{"@type":"' + pkg + '","_name" : "alpha" , "_other":null,"fake":"_typeArray"},{"@type": "' + pkg + '","_name":"beta","_other":null}]')
        assertTrue(wilma != null)
        assertTrue(wilma.length == 2)
        assertTrue(wilma[0] == (new TestObject("alpha")))
        assertTrue(wilma[1] == (new TestObject("beta")))
    }

    @Test
    void testRootTypes()
    {
        assertEquals(25L, TestUtil.toJava("25"))
        assertEquals(25.0d, TestUtil.toJava("25.0") as Double, 0.00001d)
        assertEquals(true, TestUtil.toJava("true"))
        assertEquals(false, TestUtil.toJava("false"))
        assertEquals("foo", TestUtil.toJava('"foo"'))
    }

    @Test
    void testRoots2()
    {
        // Test root JSON type as [ ]
        Object array = ['Hello'] as Object[]
        String json = TestUtil.toJson(array)
        Object oa = TestUtil.toJava(json)
        assertTrue(oa.getClass().isArray())
        assertTrue(((Object[]) oa)[0].equals("Hello"))

        // Test root JSON type as { }
        Calendar cal = Calendar.getInstance()
        cal.set(1965, 11, 17)
        json = TestUtil.toJson(cal)
        TestUtil.printLine("json = " + json)
        Object obj = TestUtil.toJava(json)
        assertTrue(!obj.getClass().isArray())
        Calendar date = (Calendar) obj;
        assertTrue(date.get(Calendar.YEAR) == 1965)
        assertTrue(date.get(Calendar.MONTH) == 11)
        assertTrue(date.get(Calendar.DAY_OF_MONTH) == 17)
    }

    @Test
    void testNull()
    {
        String json = TestUtil.toJson(null)
        TestUtil.printLine('json=' + json)
        assert 'null' == json
    }

    @Test
    void testEmptyObject()
    {
        Object o = TestUtil.toJava('{}')
        assert JsonObject.class == o.getClass()

        Object[] oa = (Object[]) TestUtil.toJava('[{},{}]')
        assert oa.length == 2
        assert JsonObject.class == oa[0].getClass()
        assert JsonObject.class == oa[1].getClass()
    }
}
