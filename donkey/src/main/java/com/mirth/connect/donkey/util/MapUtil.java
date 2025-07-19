/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.donkey.util;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.donkey.model.message.InvalidMapValue;
import com.mirth.connect.donkey.util.DonkeyElement.DonkeyElementException;
import com.mirth.connect.donkey.util.xstream.SerializerException;

public class MapUtil {
    private static Logger logger = LogManager.getLogger(MapUtil.class);

    public static String serializeMap(Serializer serializer, Map<String, Object> map) {
        if (hasInvalidValues(map)) {
            try {
                DonkeyElement mapElement = new DonkeyElement("<map/>");

                for (Entry<String, Object> entry : map.entrySet()) {
                    DonkeyElement entryElement = mapElement.addChildElement("entry");
                    entryElement.addChildElement("string", entry.getKey());

                    String valueXML = "";

                    if (entry.getValue() instanceof InvalidMapValue) {
                        valueXML = ((InvalidMapValue) entry.getValue()).getValueXML();
                    } else {
                        try {
                            valueXML = serializer.serialize(entry.getValue());
                        } catch (Exception e) {
                            logger.warn("Non-serializable value found in map, converting value to string with key: " + entry.getKey());
                            valueXML = String.valueOf((entry.getValue() == null) ? "" : entry.getValue().toString());
                        }
                    }

                    entryElement.addChildElementFromXml(valueXML);
                }

                return mapElement.toXml();
            } catch (DonkeyElementException e) {
                throw new SerializerException(e);
            }
        } else {
            /*
             * Try to serialize the entire map and if it fails, then find the map values that failed
             * to serialize and convert them into their string representation before attempting to
             * serialize again.
             */
            try {
                return serializer.serialize(map);
            } catch (Exception e) {
                Map<String, Object> newMap = new HashMap<String, Object>();

                for (Entry<String, Object> entry : map.entrySet()) {
                    Object value = entry.getValue();

                    try {
                        serializer.serialize(value);
                        newMap.put(entry.getKey(), value);
                    } catch (Exception e2) {
                        logger.warn("Non-serializable value found in map, converting value to string with key: " + entry.getKey());
                        newMap.put(entry.getKey(), (value == null) ? "" : value.toString());
                    }
                }

                return serializer.serialize(newMap);
            }
        }
    }

    public static boolean hasInvalidValues(Map<String, Object> map) {
        for (Object mapValue : map.values()) {
            if (mapValue instanceof InvalidMapValue) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deserializeMap(Serializer serializer, String serializedMap) {
        try {
            return (Map<String, Object>) serializer.deserialize(serializedMap, Map.class);
        } catch (Exception e) {
            try {
                return deserializeMapWithInvalidValues(serializer, new DonkeyElement(serializedMap));
            } catch (DonkeyElementException e2) {
                throw new SerializerException(e2);
            }
        }
    }

    public static Map<String, Object> deserializeMapWithInvalidValues(Serializer serializer, DonkeyElement mapElement) {
        /*
         * If an exception occurs while deserializing, we build up a new map manually, attempting to
         * deserialize each entry and replacing entries that fail with their string representations.
         */
        Map<String, Object> map = new HashMap<String, Object>();

        for (DonkeyElement entry : mapElement.getChildElements()) {
            if (!entry.getNodeName().equalsIgnoreCase("entry")) {
                // If the child isn't an entry node, assume it's an intermediate delegate map (like "m" for unmodifiable maps).
                map.putAll(deserializeMapWithInvalidValues(serializer, entry));
            } else if (entry.getChildElements().size() > 1) {
                String key = entry.getChildElements().get(0).getTextContent();
                String valueXML = "";
                Object value = null;

                try {
                    valueXML = entry.getChildElements().get(1).toXml();
                    value = serializer.deserialize(valueXML, Object.class);
                } catch (Exception e2) {
                    value = new InvalidMapValue(valueXML);
                }

                map.put(key, value);
            }
        }

        return map;
    }

    /**
     * Tries to safely put all entries from a possibly concurrently modified source map into the
     * target map. Retries up to the specified maxAttempts if ConcurrentModificationException is
     * thrown, sleeping 10ms between retries.
     *
     * @param target
     *            The destination map
     * @param source
     *            The source map that might be modified concurrently
     * @param maxAttempts
     *            The maximum number of retries
     * @return true if the putAll succeeded, false if it failed after retries
     */
    public static <K, V> boolean safePutAll(Map<K, V> target, Map<K, V> source, final int maxAttempts) {

        int attempts = 0;
        while (attempts++ < maxAttempts) {
            try {
                target.putAll(source);
                return true;
            } catch (ConcurrentModificationException e) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }
}
