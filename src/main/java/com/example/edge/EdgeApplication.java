package com.example.edge;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@SpringBootApplication
public class EdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeApplication.class, args);
    }

    @Bean
    SecurityWebFilterChain authorization(ServerHttpSecurity httpSecurity) {
        return httpSecurity
                .httpBasic(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ae -> ae
                        .pathMatchers("/proxy").authenticated()
                        .anyExchange().permitAll()
                )
                .build();
    }

    @Bean
    MapReactiveUserDetailsService userDetailsService() {
        return new MapReactiveUserDetailsService(User.withDefaultPasswordEncoder()
                .password("pw")
                .username("jlong").roles("USER").build());
    }

    @Bean
    RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(5, 7);
    }

    @Bean
    RouteLocator gateway(
            RedisRateLimiter redisRateLimiter,
            RouteLocatorBuilder rlb) {
        return rlb
                .routes()
                .route(rs -> rs
                        .path("/proxy").and().host("*.spring.io")
                        .filters(fs ->
                                fs
                                        .setPath("/customers")
                                        .addResponseHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                        .requestRateLimiter(rl -> rl.setRateLimiter(redisRateLimiter))
                        )
                        .uri("http://localhost:8082/")
                )
                .build();
    }

    @Bean
    RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
        return builder.tcp("localhost", 8181);
    }

    @Bean
    WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}

@Controller
@ResponseBody
class CrmClientRestController {

    private final CrmClient crmClient;

    CrmClientRestController(CrmClient crmClient) {
        this.crmClient = crmClient;
    }

    @GetMapping("/cos")
    Flux<CustomerOrders> getCustomerOrders() {
        return this.crmClient.getCustomerOrders();
    }
}

record Customer(@JsonProperty("name") String name, @JsonProperty("id") Integer id) {
}

record Order(@JsonProperty("id") Integer id, @JsonProperty("customerId") Integer customerId) {
}

@Component
class CrmClient {

    private final WebClient http;
    private final RSocketRequester rSocketRequester;

    CrmClient(WebClient http, RSocketRequester rSocketRequester) {
        this.http = http;
        this.rSocketRequester = rSocketRequester;
    }

    Flux<Order> getOrdersFor(Integer customerId) {
        return this.rSocketRequester.route("orders.{cid}", customerId).retrieveFlux(Order.class)
                .retryWhen(Retry.backoff(10, Duration.ofSeconds(1)))
                .onErrorResume(ex -> Flux.empty())
                .timeout(Duration.ofSeconds(10));
    }

    Flux<Customer> getCustomers() {
        return this.http.get().uri("http://localhost:8082/customers").retrieve().bodyToFlux(Customer.class)
                .retryWhen(Retry.backoff(10, Duration.ofSeconds(1)))
                .onErrorResume(ex -> Flux.empty())
                .timeout(Duration.ofSeconds(10));
    }

    Flux<CustomerOrders> getCustomerOrders() {
        return getCustomers()
                .flatMap(c -> Mono.zip(Mono.just(c), getOrdersFor(c.id()).collectList()))
                .map(tuple2 -> new CustomerOrders(tuple2.getT1(), tuple2.getT2()));
    }


}

record CustomerOrders(Customer customer, List<Order> orders) {
}

@Controller
class CrmClientGraphqlController {

    private final CrmClient crm;

    CrmClientGraphqlController(CrmClient crm) {
        this.crm = crm;
    }

    @SchemaMapping(typeName = "Customer", value = "orders")
    Flux<Order> orders(Customer customer) {
        return this.crm.getOrdersFor(customer.id());
    }

    @QueryMapping
    Flux<Customer> customers() {
        return this.crm.getCustomers();
    }


}