package com.cedarsoftware.io;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.cedarsoftware.io.JsonReader.MissingFieldHandler;
import com.cedarsoftware.util.ClassUtilities;
import com.cedarsoftware.util.convert.Converter;

/**
 * This class is used to convert a source of Java Maps that were created from
 * the JsonParser.  These are in 'raw' form with no 'pointers'.  This code will
 * reconstruct the 'shape' of the graph by connecting @ref's to @ids.
 * <p>
 * The subclasses that override this class can build an object graph using Java
 * classes or a Map-of-Map representation.  In both cases, the @ref value will
 * be replaced with the Object (or Map) that had the corresponding @id.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License");
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         <a href="http://www.apache.org/licenses/LICENSE-2.0">License</a>
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Resolver {
    private static final String NO_FACTORY = "_︿_ψ_☼";
    final Collection<UnresolvedReference> unresolvedRefs = new ArrayList<>();
    private final Map<Object, Object> visited = new IdentityHashMap<>();
    protected final Deque<JsonObject> stack = new ArrayDeque<>();
    private final Collection<JsonObject> mapsToRehash = new ArrayList<>();
    // store the missing field found during deserialization to notify any client after the complete resolution is done
    final Collection<Missingfields> missingFields = new ArrayList<>();
    private ReadOptions readOptions;
    private ReferenceTracker references;
    private final Converter converter;
    private SealedSupplier sealedSupplier = new SealedSupplier();

    /**
     * UnresolvedReference is created to hold a logical pointer to a reference that
     * could not yet be loaded, as the @ref appears ahead of the referenced object's
     * definition.  This can point to a field reference or an array/Collection element reference.
     */
    static final class UnresolvedReference {
        private final JsonObject referencingObj;
        private String field;
        private final long refId;
        private int index = -1;

        UnresolvedReference(JsonObject referrer, String fld, long id) {
            referencingObj = referrer;
            field = fld;
            refId = id;
        }

        UnresolvedReference(JsonObject referrer, int idx, long id) {
            referencingObj = referrer;
            index = idx;
            refId = id;
        }
    }

    /**
     * stores missing fields information to notify client after the complete deserialization resolution
     */
    protected static class Missingfields {
        private final Object target;
        private final String fieldName;
        private final Object value;

        public Missingfields(Object target, String fieldName, Object value) {
            this.target = target;
            this.fieldName = fieldName;
            this.value = value;
        }
    }

    protected Resolver(ReadOptions readOptions, ReferenceTracker references, Converter converter) {
        this.readOptions = readOptions;
        this.references = references;
        this.converter = converter;
    }

    public ReadOptions getReadOptions() {
        return readOptions;
    }

    public ReferenceTracker getReferences() {
        return references;
    }

    public Converter getConverter() {
        return converter;
    }

    /**
     * This method converts a rootObj Map, (which contains nested Maps
     * and so forth representing a Java Object graph), to a Java
     * object instance.  The rootObj map came from using the JsonReader
     * to parse a JSON graph (using the API that puts the graph
     * into Maps, not the typed representation).
     *
     * @param rootObj JsonObject instance that was the rootObj object from the
     * @param root    When you know the type you will be returning.  Can be null (effectively Map.class)
     *                JSON input that was parsed in an earlier call to JsonReader.
     * @return a typed Java instance that was serialized into JSON.
     */
    @SuppressWarnings("unchecked")
    public <T> T toJavaObjects(JsonObject rootObj, Class<T> root) {
        if (rootObj == null) {
            return null;
        }

        if (rootObj.isReference()) {
            rootObj = getReferences().get(rootObj.refId);
            if (rootObj != null) {
                return (T) rootObj;
            }
        }

        if (rootObj.isFinished) {   // Called on a JsonObject that has already been converted
            return (T) rootObj.getTarget();
        } else {
            rootObj.setHintType(root);
            Object instance = rootObj.getTarget() == null ? createInstance(rootObj) : rootObj.getTarget();
            if (rootObj.isFinished) {   // Factory method instantiated and completely loaded the object.
                return (T) instance;
            } else {
                return traverseJsonObject(rootObj);
            }
        }
    }

    /**
     * Walk a JsonObject (Map of String keys to values) and return the
     * Java object equivalent filled in as good as possible (everything
     * except unresolved reference fields or unresolved array/collection elements).
     *
     * @param root JsonObject reference to a Map-of-Maps representation of the JSON
     *             input after it has been completely read.
     * @return Properly constructed, typed, Java object graph built from a Map
     * of Maps representation (JsonObject root).
     */
    public <T> T traverseJsonObject(JsonObject root) {
        push(root);

        while (!stack.isEmpty()) {
            final JsonObject jsonObj = stack.pop();

            if (jsonObj.isReference()) {
                continue;
            }
            if (jsonObj.isFinished) {
                continue;
            }
            if (visited.containsKey(jsonObj)) {
                jsonObj.setFinished();
                continue;
            }
            visited.put(jsonObj, null);
            traverseSpecificType(jsonObj);
        }
        return (T) root.getTarget();
    }

    public void traverseSpecificType(JsonObject jsonObj) {
        if (jsonObj.isFinished) {
            return;
        }
        if (jsonObj.isArray()) {
            traverseArray(jsonObj);
        } else if (jsonObj.isCollection()) {
            traverseCollection(jsonObj);
        } else if (jsonObj.isMap()) {
            traverseMap(jsonObj);
        } else {
            Object special;
            if ((special = readWithFactoryIfExists(jsonObj, null)) != null) {
                jsonObj.setTarget(special);
            } else {
                traverseFields(jsonObj);
            }
        }
    }

    public SealedSupplier getSealedSupplier() {
        return sealedSupplier;
    }

    /**
     * Push a JsonObject on the work stack that has not yet had it's fields move over to it's Java peer (.target)
     * @param jsonObject JsonObject that supplies the source values for the Java peer (target)
     */
    public void push(JsonObject jsonObject) {
        stack.push(jsonObject);
    }

    public abstract void traverseFields(final JsonObject jsonObj);

    protected abstract Object readWithFactoryIfExists(final Object o, final Class<?> compType);

    protected abstract void traverseCollection(JsonObject jsonObj);

    protected abstract void traverseArray(JsonObject jsonObj);

    protected void cleanup() {
        patchUnresolvedReferences();
        rehashMaps();
        references.clear();
        unresolvedRefs.clear();
        mapsToRehash.clear();
        handleMissingFields();
        missingFields.clear();
        stack.clear();
        visited.clear();
        references = null;
        readOptions = null;
        sealedSupplier.seal();
        sealedSupplier = null;
    }

    // calls the missing field handler if any for each recorded missing field.
    private void handleMissingFields() {
        MissingFieldHandler missingFieldHandler = readOptions.getMissingFieldHandler();
        if (missingFieldHandler != null) {
            for (Missingfields mf : missingFields) {
                missingFieldHandler.fieldMissing(mf.target, mf.fieldName, mf.value);
            }
        }//else no handler so ignore.
    }

    /**
     * Process java.util.Map and it's derivatives.  These are written specially
     * so that the serialization does not expose the class internals
     * (internal fields of TreeMap for example).
     *
     * @param jsonObj a Map-of-Map representation of the JSON input stream.
     */
    protected void traverseMap(JsonObject jsonObj) {
        Map.Entry<Object, Object> pair = jsonObj.asTwoArrays();
        final Object keys = pair.getKey();
        final Object items = pair.getValue();

        if (keys == null) {  // If keys is null, items is also null due to JsonObject validation
            return;
        }

        buildCollection(keys);
        buildCollection(items);

        // Save these for later so that unresolved references inside keys or values
        // get patched first, and then build the Maps.
        mapsToRehash.add(jsonObj);
    }

    private void buildCollection(Object arrayContent) {
        final JsonObject collection = new JsonObject();
        collection.setItems(arrayContent);
        collection.setTarget(arrayContent);
        push(collection);
    }

    /**
     * This method creates a Java Object instance based on the passed in parameters.
     * If the JsonObject contains a key '@type' then that is used, as the type was explicitly
     * set in the JSON stream.  If the key '@type' does not exist, then the passed in Class
     * is used to create the instance, handling creating an Array or regular Object
     * instance.<p></p>
     * The '@type' is not often specified in the JSON input stream, as in many
     * cases it can be inferred from a field reference or array component type.
     * For Enum handling, the following rules are applied:
     * <p><pre>
     * Detection Rules:
     * 1. Single Enum Instance
     *    Detect:  javaType is an enum class (enumClass != null) AND jsonObj.getItems() == null
     *    Create:  Single enum instance using enum class
     *    Factory: EnumClassFactory
     *
     * 2. EnumSet Instance
     *    Detect:  javaType is an enum class (enumClass != null) AND jsonObj.getItems() != null
     *    Create:  EnumSet using enum class
     *    Factory: EnumSetFactory
     *
     * 3. Regular Class
     *    Detect:  Non-enum javaType (enumClass == null)
     *    Create:  Regular class instance
     *    Factory: Standard Factory Logic
     * </pre>
     *
     * @param jsonObj Map-of-Map representation of object to create.
     * @return a new Java object of the appropriate type (clazz) using the jsonObj to provide
     * enough hints to get the right class instantiated.  It is not populated when returned.
     */
    Object createInstance(JsonObject jsonObj) {
        Object target = jsonObj.getTarget();
        if (target != null) {
            return target;
        }

        // Resolve target type with proper coercion and enum handling
        Class<?> targetType = resolveTargetType(jsonObj);

        // Determine the factory type, considering enums and collections
        Class<?> factoryType = determineFactoryType(jsonObj, targetType);

        // Try creating an instance using the class factory
        Object mate = createInstanceUsingClassFactory(factoryType, jsonObj);
        if (mate != NO_FACTORY) {
            return mate;
        }

        // Attempt conversion using the Converter
        Object sourceValue = jsonObj.hasValue() ? jsonObj.getValue() : null;
        Class<?> sourceType = sourceValue != null ? sourceValue.getClass() : (!jsonObj.isEmpty() ? Map.class : null);

        // Other than exceptions, see if there is a converter that can handle the conversion
        if (!Throwable.class.isAssignableFrom(targetType)) {
            if (sourceType != null && converter.isConversionSupportedFor(sourceType, targetType)) {
                try {
                    Object value = converter.convert(sourceValue != null ? sourceValue : jsonObj, targetType);
                    return jsonObj.setFinishedTarget(value, true);
                } catch (Exception ignored) {
                }
            }
        }

        // Handle array creation
        if (shouldCreateArray(jsonObj, targetType)) {
            mate = createArrayInstance(jsonObj, targetType);
            return jsonObj.setTarget(mate);
        }

        // Fallback to creating an instance using the type directly
        return createInstanceUsingType(jsonObj);
    }

    // Resolve target type with proper coercion and enum handling
    private Class<?> resolveTargetType(JsonObject jsonObj) {
        Class<?> targetType = coerceClassIfNeeded(jsonObj.getJavaType());
        jsonObj.setJavaType(targetType);

        Class<?> enumClass = ClassUtilities.getClassIfEnum(targetType);
        if (enumClass != null) {
            Class<?> coercedEnumClass = getCoercedEnumClass(enumClass);
            if (coercedEnumClass != null) {
                targetType = coercedEnumClass;
                jsonObj.setJavaType(coercedEnumClass);
            }
        }
        return targetType;
    }

    private Class<?> coerceClassIfNeeded(Class<?> type) {
        if (type == null) {
            return null;
        }
        Class clazz = readOptions.getCoercedClass(type);
        return clazz == null ? type : clazz;
    }

    private Class<?> getCoercedEnumClass(Class<?> enumClass) {
        Class<?> coercedClass = readOptions.getCoercedClass(enumClass);
        if (coercedClass != null) {
            return ClassUtilities.getClassIfEnum(coercedClass);
        }
        return null;
    }

    // Determine the factory type, considering enums and collections
    private Class<?> determineFactoryType(JsonObject jsonObj, Class<?> targetType) {
        Class<?> enumClass = ClassUtilities.getClassIfEnum(targetType);
        if (enumClass != null) {
            boolean isEnumSet = jsonObj.getItems() != null;
            return isEnumSet ? EnumSet.class : enumClass;
        }
        return targetType;
    }

    private boolean shouldCreateArray(JsonObject jsonObj, Class<?> targetType) {
        Object items = jsonObj.getItems();
        return targetType.isArray() || (items != null && targetType == Object.class && jsonObj.getKeys() == null);
    }

    private Object createArrayInstance(JsonObject jsonObj, Class<?> targetType) {
        Object items = jsonObj.getItems();
        int size = (items == null) ? 0 : Array.getLength(items);
        Class<?> componentType = targetType.isArray() ? targetType.getComponentType() : Object.class;
        return Array.newInstance(componentType, size);
    }

    /**
     * Create an instance of a Java class using the JavaType field on the jsonObj.
     */
    private Object createInstanceUsingType(JsonObject jsonObj) {
        Class<?> c = jsonObj.getJavaType();
        boolean useMaps = readOptions.isReturningJsonObjects();
        Object mate;

        if (c == Object.class && !useMaps) {  // JsonObject
            Class<?> unknownClass = readOptions.getUnknownTypeClass();
            if (unknownClass == null) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.setJavaType(Map.class);
                mate = jsonObject;
            } else {
                mate = ClassUtilities.newInstance(converter, unknownClass, null);   // can add constructor arg values
            }
        } else {
            // Handle regular field.object reference
            // ClassFactory already consulted above, likely regular business/data classes.
            // If the newInstance(c) fails, it throws a JsonIoException.
            mate = ClassUtilities.newInstance(converter, c, null);  // can add constructor arg values
        }
        return jsonObj.setTarget(mate);
    }

    /**
     * If a ClassFactory is associated to the passed in Class (clazz), then use the ClassFactory
     * to create an instance.  If a ClassFactory creates the instance, it may optionally load
     * the values into the instance, using the values from the passed in JsonObject.  If the
     * ClassFactory instance creates AND loads the object, it is indicated on the ClassFactory
     * by the isObjectFinal() method returning true.  Therefore, the JsonObject instance that is
     * loaded, is marked with 'isFinished=true' so that no more processing is needed for this instance.
     */
    Object createInstanceUsingClassFactory(Class c, JsonObject jsonObj) {
        // If a ClassFactory exists for a class, use it to instantiate the class.  The ClassFactory
        // may optionally load the newly created instance, in which case, the JsonObject is marked finished, and
        // return.

        JsonReader.ClassFactory classFactory = readOptions.getClassFactory(c);

        if (classFactory == null) {
            return NO_FACTORY;
        }

        Object target = classFactory.newInstance(c, jsonObj, this);

        // don't pass in classFactory.isObjectFinal, only set it to true if classFactory says its so.
        // it allows the factory itself to set final on the jsonObj internally where it depends
        // on how the data comes back, but that value can be a hard true if the factory knows
        // it's always true.
        if (classFactory.isObjectFinal()) {
            return jsonObj.setFinishedTarget(target, true);
        }

        return jsonObj.setTarget(target);
    }

    /**
     * For all fields where the value was "@ref":"n" where 'n' was the id of an object
     * that had not yet been encountered in the stream, make the final substitution.
     */
    private void patchUnresolvedReferences() {
        for (UnresolvedReference ref : unresolvedRefs) {
            Object objToFix = ref.referencingObj.getTarget();
            JsonObject objReferenced = this.references.getOrThrow(ref.refId);

            if (ref.index >= 0) {    // Fix []'s and Collections containing a forward reference.
                if (objToFix instanceof List) {
                    List list = (List) objToFix;
                    list.set(ref.index, objReferenced.getTarget());
                } else if (objToFix instanceof Collection) {   // Patch up Indexable Collections
                    Collection col = (Collection) objToFix;
                    col.add(objReferenced.getTarget());
                } else {
                    Array.set(objToFix, ref.index, objReferenced.getTarget());        // patch array element here
                }
            } else {    // Fix field forward reference
                Field field = getReadOptions().getDeepDeclaredFields(objToFix.getClass()).get(ref.field);
                if (field != null) {
                    try {
                        MetaUtils.setFieldValue(field, objToFix, objReferenced.getTarget());    // patch field here
                    } catch (Exception e) {
                        throw new JsonIoException("Error setting field while resolving references '" + field.getName() + "', @ref = " + ref.refId, e);
                    }
                }
            }
        }
        unresolvedRefs.clear();
    }

    /**
     * Process Maps/Sets (fix up their internal indexing structure)
     * This is required because Maps hash items using hashCode(), which will
     * change between VMs.  Rehashing the map fixes this.
     * <br>
     * If useMaps==true, then move @keys to keys and @items to values
     * and then drop these two entries from the map.
     * <br>
     * This hashes both Sets and Maps because the JDK sets are implemented
     * as Maps.  If you have a custom-built Set, this would not 'treat' it,
     * and you would need to provide a custom reader for that set.
     */
    private void rehashMaps() {
        for (JsonObject jsonObj : mapsToRehash) {
            jsonObj.rehashMaps();
        }
    }

    public boolean valueToTarget(JsonObject jsonObject) {
        if (jsonObject.javaType == null) {
            if (jsonObject.hintType == null) {
                return false;
            }
            jsonObject.javaType = jsonObject.hintType;
        }

        Class<?> javaType = jsonObject.javaType;
        // TODO: Support multiple dimensions
        // TODO: Support char
        if (javaType.isArray() && converter.isSimpleTypeConversionSupported(javaType.getComponentType(), javaType)) {
            Object jsonItems = jsonObject.getItems();
            Class<?> componentType = javaType.getComponentType();
            if (jsonItems == null) {    // empty array
                jsonObject.setFinishedTarget(null, true);
                return true;
            }
            int len = Array.getLength(jsonItems);
            Object javaArray = Array.newInstance(componentType, len);
            for (int i = 0; i < len; i++) {
                try {
                    Class<?> type = componentType;
                    Object item = Array.get(jsonItems, i);
                    if (item instanceof JsonObject) {
                        JsonObject jObj = (JsonObject) item;
                        if (jObj.getJavaType() != null) {
                            type = jObj.getJavaType();
                        }
                    }
                    Array.set(javaArray, i, converter.convert(item, type));
                } catch (Exception e) {
                    JsonIoException jioe = new JsonIoException(e.getMessage());
                    jioe.setStackTrace(e.getStackTrace());
                    throw jioe;
                }
            }
            jsonObject.setFinishedTarget(javaArray, true);
            return true;
        }

        if (!converter.isSimpleTypeConversionSupported(javaType, javaType)) {
            return false;
        }

        try {
            Object value = converter.convert(jsonObject, javaType);
            jsonObject.setFinishedTarget(value, true);
            return true;
        } catch (Exception e) {
            JsonIoException jioe = new JsonIoException(e.getMessage());
            jioe.setStackTrace(e.getStackTrace());
            throw jioe;
        }
    }

    protected void setArrayElement(Object array, int index, Object element) {
        try {
            Array.set(array, index, element);
        } catch (Exception e) {
            String elementType = element == null ? "null" : element.getClass().getName();
            String valueRepresentation = String.valueOf(element);
            String arrayType = array.getClass().getSimpleName();

            throw new IllegalArgumentException("Cannot set '" + elementType + "' (value: " + valueRepresentation + ") into '" +
                    arrayType + "' at index " + index + ". Type mismatch between value and array type.");
        }
    }

    protected abstract Object resolveArray(Class<?> suggestedType, List<Object> list);
}