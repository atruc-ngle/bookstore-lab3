package vn.edu.bookstore.order.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.bookstore.order.TestcontainersConfig;
import vn.edu.bookstore.order.support.CatalogStub;

/** TH3-6: catalog chậm hơn timeout đọc → 503 nhanh; sau đủ số lỗi cầu dao MỞ và không gọi catalog nữa. */
@SpringBootTest(properties = {
        "bookstore.catalog.read-timeout=300ms",
        "bookstore.catalog.circuit-breaker.sliding-window-size=4",
        "bookstore.catalog.circuit-breaker.minimum-number-of-calls=2",
        "bookstore.catalog.circuit-breaker.failure-rate-threshold=50",
        "bookstore.catalog.circuit-breaker.wait-duration-in-open-state=60s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CatalogCircuitBreakerTest {

    static final WireMockServer CATALOG = CatalogStub.start();

    static {
        CATALOG.stubFor(WireMock.get(urlMatching("/api/v1/books/.*"))
                .willReturn(aResponse().withFixedDelay(5000).withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("bookstore.catalog.base-url", CATALOG::baseUrl);
    }

    @AfterAll
    static void stop() {
        CATALOG.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CircuitBreakerRegistry registry;

    @Test
    void timeoutRoiCauDaoMo() throws Exception {
        String body = "{\"customerId\":88,\"lines\":[{\"isbn\":\"9780134685991\",\"quantity\":1}]}";
        List<Long> thoiGian = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "10"))
                    .andExpect(jsonPath("$.type").value("https://api.bookstore.vn/problems/upstream-unavailable"));
            thoiGian.add((System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("THOI GIAN 5 LAN GOI (ms): " + thoiGian);
        assertThat(thoiGian).as("không lần nào chờ hết 5 s của catalog chậm")
                .allSatisfy(t -> assertThat(t).isLessThan(4000));
        assertThat(thoiGian.subList(2, 5)).as("cầu dao đã mở → trả lỗi ngay")
                .allSatisfy(t -> assertThat(t).isLessThan(500));
        // cầu dao "catalog" phải đang MỞ; nhờ đó không phải cả 5 lời gọi đều chạm tới catalog
        assertThat(registry.circuitBreaker("catalog").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(CATALOG.findAll(getRequestedFor(urlMatching("/api/v1/books/.*")))).hasSizeLessThan(5);
    }
}
