package org.example.mpstlibrary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;

@Configuration
public class LockConfig {
    /**
     * Lock registry backed by Redis. The third argument is the lock expiry in
     * milliseconds — if a holder dies, the lock auto-releases after this.
     * Set high enough to outlast normal critical sections; short enough that
     * a crashed holder doesn't block forever.
     */
    @Bean
    public RedisLockRegistry redisLockRegistry(RedisConnectionFactory connectionFactory) {
        return new RedisLockRegistry(connectionFactory, "mpst-protocol-locks", 10_000);
    }
}
