package org.example.mpstlibrary;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.BDDMockito.given;

@SpringBootTest
class MpstLibraryApplicationTests {

    @MockitoBean
    private RedisTemplate<String, String> redisTemplate;

    // We likely need to mock the operations too since
    // ProtocolStateManagementService calls opsForValue()
    @MockitoBean
    private ValueOperations<String, String> valueOperations;

    @MockitoBean
    private org.example.mpstlibrary.repo.ProtocolRepository protocolRepository;

    @Test
    void contextLoads() {
        // Setup the mock to return valueOperations when opsForValue is called
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

}
