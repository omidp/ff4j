package org.ff4j.store;

/*-
 * #%L
 * ff4j-store-redis
 * %%
 * Copyright (C) 2013 - 2024 FF4J
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.ff4j.audit.Event;
import org.ff4j.audit.EventQueryDefinition;
import org.ff4j.audit.EventSeries;
import org.ff4j.audit.MutableHitCount;
import org.ff4j.audit.chart.TimeSeriesChart;
import org.ff4j.audit.repository.AbstractEventRepository;
import org.ff4j.redis.RedisKeysBuilder;
import org.ff4j.utils.Util;


import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;

/**
 * Persist audit events into REDIS storage technology.
 *
 * @author clunven
 * @author Shridhar Navanageri
 */
public class EventRepositoryRedisLettuce extends AbstractEventRepository {

    public static final int UPPER_LIMIT = 50000;
    
    /**
     * Jackson ObjectMapper for serialization and deserialization purpose.
     */
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    /** Lettuce client. */ 
    private RedisCommands<String, String> redisCommands;
    
    /** Support the cluster based redis deployment. */
    private RedisAdvancedClusterCommands<String, String> redisCommandsCluster;
    
    /** Default key builder. */
    private RedisKeysBuilder keyBuilder = new RedisKeysBuilder();
    
    /**
     * Public void.
     */
    public EventRepositoryRedisLettuce(RedisClient redisClient) {
        this(redisClient, new RedisKeysBuilder());
    }
    public EventRepositoryRedisLettuce(RedisClient redisClient, RedisKeysBuilder keyBuilder) {
        this.redisCommands = redisClient.connect().sync();
        this.keyBuilder    = keyBuilder;
    }
    public EventRepositoryRedisLettuce(RedisClusterClient redisClusterClient) {
        this(redisClusterClient, new RedisKeysBuilder());
    }
    public EventRepositoryRedisLettuce(RedisClusterClient redisClusterClient, RedisKeysBuilder keyBuilder) {
        this.redisCommandsCluster = redisClusterClient.connect().sync();
        this.keyBuilder    = keyBuilder;
    }

    static {
        // Avoiding accidental breaks, since Redis is a schema-free store.
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Enumeration containing the type of the attribute. */
    private enum Types {
        SOURCE,
        NAME,
        HOST,
        USER;
    }
  
    /** {@inheritDoc} */
    @Override
    public void createSchema() {
        // Keys are automatically generated and created
    }

    /** {@inheritDoc} */
    public boolean saveEvent(Event evt) {
        if (evt == null) {
            throw new IllegalArgumentException("Event cannot be null nor empty");
        }
        try {
            long timeStamp = evt.getTimestamp();
            String hashId = keyBuilder.getHashKey(evt.getTimestamp());
            evt.setUuid(String.valueOf(timeStamp));
            if (null != redisCommands) {
                redisCommands.zadd(hashId, timeStamp, objectMapper.writeValueAsString(evt));
            } else {
                redisCommandsCluster.zadd(hashId, timeStamp, objectMapper.writeValueAsString(evt));
            }
            return true;
        } catch (JsonProcessingException e) {
            // We do not returned false, it will be retried 3 times for nothing, faile immediately
            throw new IllegalArgumentException("Cannot save event : invalid object", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Event getEventByUUID(String uuid, Long timestamp) {
        Util.assertHasLength(new String[]{uuid});
        Event redisEvent = null;
        String hashKey = keyBuilder.getHashKey(timestamp);
            
        // Check for the event within 100ms time range passed, hoping there won't be more than 10 for this.
        @SuppressWarnings("deprecation")
        List<String> events = (null != redisCommands) ? 
                redisCommands.zrangebyscore(hashKey, timestamp - 100L, timestamp + 100L, 0, 10) :
                redisCommandsCluster.zrangebyscore(hashKey, timestamp - 100L, timestamp + 100L, 0, 10);

        // Loop through the result set and match the timestamp passed in.
        for (String evt : events) {
            Event event = marshallEvent(evt);
            if (timestamp == event.getTimestamp()) {
                return event;
            }
        }
        return redisEvent;
    }
    
    private Event marshallEvent(String eventString) {
        try {
            return objectMapper.readValue(eventString, Event.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Cannot read event from DB, cannot parse", e);
        } catch (JsonMappingException e) {
            throw new IllegalArgumentException("Cannot read event from DB, cannot map", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read event from DB", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MutableHitCount> getFeatureUsageHitCount(EventQueryDefinition query) {
        return getUsageCount(query, Types.NAME);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MutableHitCount> getHostHitCount(EventQueryDefinition query) {
        return getUsageCount(query, Types.HOST);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MutableHitCount> getUserHitCount(EventQueryDefinition query) {
        return getUsageCount(query, Types.USER);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MutableHitCount> getSourceHitCount(EventQueryDefinition query) {
        return getUsageCount(query, Types.SOURCE);
    }

    private Map<String, MutableHitCount> getUsageCount(EventQueryDefinition query, Types type) {
        Map<String, MutableHitCount> hitCount = new HashMap<>();
       // Get events from Redis between the time range.
            Set<String> events = getEventsFromRedis(query);
            // Loop through create the buckets.
            for (String event : events) {
                Event eventObject = marshallEvent(event);
                String value = getValueFromAttribute(type, eventObject);
                MutableHitCount mutableHitCount = hitCount.get(value);
                if (mutableHitCount != null) {
                    mutableHitCount.inc();
                } else {
                    mutableHitCount = new MutableHitCount(1);
                }
                hitCount.put(value, mutableHitCount);
            }
        return hitCount;
    }

    /** {@inheritDoc} */
    @Override
    public TimeSeriesChart getFeatureUsageHistory(EventQueryDefinition query, TimeUnit tu) {
        TimeSeriesChart tsc = new TimeSeriesChart(query.getFrom(), query.getTo(), tu);
        Set<String> events = getEventsFromRedis(query);
        for (String event : events) {
            tsc.addEvent(marshallEvent(event));
        }
        return tsc;
    }

    /** {@inheritDoc} */
    @Override
    public EventSeries searchFeatureUsageEvents(EventQueryDefinition query) {
        // Referenced from RDBMS implementation, same usage.
        return getAuditTrail(query);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("deprecation")
    public EventSeries getAuditTrail(EventQueryDefinition query) {
        EventSeries eventSeries = new EventSeries();
        String hashKey = keyBuilder.getHashKey(query.getFrom());
        List<String> events = (null != redisCommands) ? 
                redisCommands.zrangebyscore(hashKey, query.getFrom(), query.getTo(), 0, 100):
                redisCommandsCluster.zrangebyscore(hashKey, query.getFrom(), query.getTo(), 0, 100);
        for (String event : events) {
            eventSeries.add(marshallEvent(event));
        }
        return eventSeries;
    }

    /** {@inheritDoc} */
    @Override
    public void purgeAuditTrail(EventQueryDefinition query) {
        // TBD: Where is the setting to turn purging on/off and what would be the time range?
    }

    /** {@inheritDoc} */
    @Override
    public void purgeFeatureUsage(EventQueryDefinition query) {
        // TBD: Where is the setting to turn purging on/off and what would be the time range?
    }

    /**
     * Method that reads the raw event stream from Redis.
     *
     * @param query - The query object containing details about the query.
     * @return Set containing raw events.
     */
    @SuppressWarnings("deprecation")
    private Set<String> getEventsFromRedis(EventQueryDefinition query) {
        List< String > eventList = (null != redisCommands) ? 
                redisCommands.zrangebyscore(
                        keyBuilder.getHashKey(query.getFrom()), query.getFrom(), query.getTo(), 0, UPPER_LIMIT) :
                redisCommandsCluster.zrangebyscore(
                        keyBuilder.getHashKey(query.getFrom()), query.getFrom(), query.getTo(), 0, UPPER_LIMIT);
        return new HashSet<>(eventList);                            
    }

    /**
     * Method that maps the enum to the appropriate event method (instead of using Reflection).
     *
     * @param type - The type of the evnt to be used.
     * @param event - Actual event object.
     * @return The value from the event object.
     */
    private String getValueFromAttribute(Types type, Event event) {
        String value;
        switch (type) {
            case HOST:
                value = event.getHostName();
                break;
            case SOURCE:
                value = event.getSource();
                break;
            case USER:
                value = event.getUser();
                break;
            case NAME:
                value = event.getName();
                break;
            default:
                value = "NA";
        }
        return value;
    }

}
