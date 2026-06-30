
Demo đơn giản SSE dùng Flux, Flux có thể sử dụng từ spring 2.xx 
#todo : demo vụ SSE có "last event ID" để xử lý vấn đề này, nếu client chủ động đóng connection, thì khi re-connect thì gửi lại last_event_id, và server dùng nó để bù gap.

![[Pasted image 20260630064903.png]]



```java
@RestController
public class controller {
    @GetMapping("/stream-sse")
    public Flux<ServerSentEvent<String>> streamEvents() {
        return Flux.interval(Duration.ofSeconds(1))
                .map(sequence -> ServerSentEvent.<String> builder()
                        .id(String.valueOf(sequence))
                        .event("periodic-event")
                        .data("SSE - " + LocalTime.now().toString())
                        .build());
    }
}
```


```java
@SpringBootApplication
public class SseDemoApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SseDemoApplication.class);
        
        app.setRegisterShutdownHook(false); 
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("server.port", "8081");
        
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");

        app.run(args);
        System.out.println("? ?ng d?ng SSE ?� kh?i ch?y th�nh c�ng t?i c?ng 8081!");
    }
}
```