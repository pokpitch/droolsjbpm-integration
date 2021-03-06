/*
 * Copyright 2010 JBoss Inc
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

package org.kie.remote.jaxb.gen.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import javax.xml.bind.annotation.adapters.XmlAdapter;

import org.kie.remote.jaxb.gen.util.JaxbListWrapper.JaxbWrapperType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Do not return an instance of Arrays.asList() -- that implementation is *not*
 * modifiable!
 *
 * See org.drools.core.xml.jaxb.util.JaxbUnknownAdapter
 *
 * * 7.0 plans:
 * - move this at least to kie-internal
 * - use JaxbObjectObjectPair instead of JaxbStringObjectPair for maps
 */
@SuppressWarnings("unchecked")
public class JaxbUnknownAdapter extends XmlAdapter<Object, Object> {

    private static final Logger logger = LoggerFactory.getLogger(JaxbUnknownAdapter.class);

    private static final Object PRESENT = new Object();

    @Override
    public Object marshal( Object o ) throws Exception {
        return recursiveMarshal(o, new IdentityHashMap<Object, Object>());
    }

    private Object recursiveMarshal( Object o, Map<Object, Object> seenObjectsMap ) {
        if( seenObjectsMap.put(o, PRESENT) != null ) {
            throw new UnsupportedOperationException("Serialization of recursive data structures is not supported!");
        }
        try {
            if( o instanceof Queue ) {
                Queue queue = (Queue) o;
                Object[] serializedArr = convertCollectionToSerializedArray(queue, seenObjectsMap);
                return new JaxbListWrapper(serializedArr, JaxbWrapperType.QUEUE);
            } else if( o instanceof List ) {
                List list = (List) o;
                Object[] serializedArr = convertCollectionToSerializedArray(list, seenObjectsMap);
                return new JaxbListWrapper(serializedArr, JaxbWrapperType.LIST);
            } else if( o instanceof Set ) {
                Set set = (Set) o;
                Object[] serializedArr = convertCollectionToSerializedArray(set, seenObjectsMap);
                return new JaxbListWrapper(serializedArr, JaxbWrapperType.SET);
            } else if( o instanceof Map ) {
                Map<Object, Object> map = (Map<Object, Object>) o;
                List<JaxbStringObjectPair> pairList = new ArrayList<JaxbStringObjectPair>(map.size());
                if( map == null || map.isEmpty() ) {
                    pairList = Collections.EMPTY_LIST;
                }

                for( Entry<Object, Object> entry : map.entrySet() ) {
                    Object key = entry.getKey();
                    if( key != null && !(key instanceof String) ) {
                        throw new UnsupportedOperationException("Only String keys for Map structures are supported [key was a "
                                + key.getClass().getName() + "]");
                    }
                    Object value = convertObjectToSerializableVariant(entry.getValue(), seenObjectsMap);
                    pairList.add(new JaxbStringObjectPair((String) key, value));
                }

                return new JaxbListWrapper(pairList.toArray(new JaxbStringObjectPair[pairList.size()]), JaxbWrapperType.MAP);
            } else {
                return o;
            }
        } finally {
            seenObjectsMap.remove(o);
        }
    }

    private Object[] convertCollectionToSerializedArray( Collection collection, Map<Object, Object> seenObjectsMap ) {
        List<Object> serializedList = new ArrayList<Object>(collection.size());
        for( Object elem : collection ) {
            elem = convertObjectToSerializableVariant(elem, seenObjectsMap);
            serializedList.add(elem);
        }
        return serializedList.toArray(new Object[serializedList.size()]);
    }

    private Object convertObjectToSerializableVariant( Object obj, Map<Object, Object> seenObjectsMap ) {
        if( obj == null ) {
            return null;
        } else if( !(obj instanceof JaxbListWrapper) && (obj instanceof Collection || obj instanceof Map) ) {
            obj = recursiveMarshal(obj, seenObjectsMap);
        }
        return obj;
    }

    @Override
    public Object unmarshal( Object o ) throws Exception {
        if( o instanceof JaxbListWrapper ) {
            JaxbListWrapper wrapper = (JaxbListWrapper) o;
            Object[] elements = wrapper.getElements();
            int size = 0;
            if( elements != null ) {
                size = elements.length;
            }
            if( wrapper.getType() == null ) {
                List<Object> list = new ArrayList<Object>(size);
                return convertSerializedElementsToCollection(elements, list);
            } else {
                switch ( wrapper.getType() ) {
                case LIST:
                    List<Object> list = new ArrayList<Object>(size);
                    return convertSerializedElementsToCollection(elements, list);
                case SET:
                    Set<Object> set = new HashSet<Object>(size);
                    return convertSerializedElementsToCollection(elements, set);
                case QUEUE:
                    Queue<Object> queue = new LinkedList<Object>();
                    return convertSerializedElementsToCollection(elements, queue);
                case MAP:
                    Map<String, Object> map = new HashMap<String, Object>(size);
                    if( size > 0 ) {
                        for( Object keyValueObj : elements ) {
                            JaxbStringObjectPair keyValue = (JaxbStringObjectPair) keyValueObj;
                            Object key = keyValue.getKey();
                            Object value = convertSerializedObjectToObject(keyValue.getValue());
                            map.put(key.toString(), value);
                        }
                    }
                    return map;
                default:
                    throw new IllegalArgumentException("Unknown JAXB collection wrapper type: " + wrapper.getType().toString());
                }
            }
        } else if( o instanceof JaxbStringObjectPair[] ) {
            // backwards compatibile: remove in 7.0.x
            JaxbStringObjectPair[] value = (JaxbStringObjectPair[]) o;
            Map<Object, Object> r = new HashMap<Object, Object>();
            for( JaxbStringObjectPair p : value ) {
                if( p.getValue() instanceof JaxbListWrapper ) {
                    r.put(p.getKey(), new ArrayList(Arrays.asList(((JaxbListWrapper) p.getValue()).getElements())));
                } else {
                    r.put(p.getKey(), p.getValue());
                }
            }
            return r;
        } else {
            return o;
        }
    }

    private Collection convertSerializedElementsToCollection( Object[] elements, Collection collection ) throws Exception {
        List<Object> list;
        if( elements == null ) {
            list = Collections.EMPTY_LIST;
        } else {
            list = new ArrayList<Object>(elements.length);
            for( Object elem : elements ) {
                elem = convertSerializedObjectToObject(elem);
                list.add(elem);
            }
        }
        collection.addAll(list);
        return collection;
    }

    private Object convertSerializedObjectToObject( Object element ) throws Exception {
        if( element == null ) {
            return element;
        }
        if( element instanceof JaxbListWrapper ) {
            element = unmarshal(element);
        }
        return element;
    }

}
