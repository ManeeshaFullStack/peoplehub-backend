package com.peoplehub.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class RedisConnectivityTest {

    @Autowired private StringRedisTemplate redis;

    @Test
    void redisRespondsToPing() {
        String reply = redis.execute((RedisCallback<String>) connection -> connection.ping());

        assertThat(reply).isEqualTo("PONG");
    }

    @Test
    void valueRoundTripsAndCarriesATtl() {
        String key = "test:b0-2:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "value", Duration.ofSeconds(30));

            assertThat(redis.opsForValue().get(key)).isEqualTo("value");
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 30L);
        } finally {
            redis.delete(key);
        }
    }
}
