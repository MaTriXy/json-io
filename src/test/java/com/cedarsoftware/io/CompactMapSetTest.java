package com.cedarsoftware.io;

import java.util.Map;
import java.util.Set;

import com.cedarsoftware.util.CompactMap;
import com.cedarsoftware.util.CompactSet;
import com.cedarsoftware.util.DeepEquals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompactMapSetTest {
    @Test
    void testCompactMap() {
        Map map = CompactMap.builder().insertionOrder().build();
        map.put("a", "alpha");
        map.put("b", "beta");
        map.put("c", "charlie");
        map.put("d", "delta");
        String json = JsonIo.toJson(map, WriteOptionsBuilder.getDefaultWriteOptions());
        Map map2 = JsonIo.toObjects(json, ReadOptionsBuilder.getDefaultReadOptions(), Map.class);
        assertTrue(DeepEquals.deepEquals(map, map2));
    }

    @Test
    void testCompactSet() {
        Set set = CompactSet.builder().insertionOrder().build();
        set.add("alpha");
        set.add("beta");
        set.add("charlie");
        set.add("delta");
        String json = JsonIo.toJson(set, WriteOptionsBuilder.getDefaultWriteOptions());
        Set set2 = JsonIo.toObjects(json, ReadOptionsBuilder.getDefaultReadOptions(), Set.class);
        assertTrue(DeepEquals.deepEquals(set, set2));
    }
}
