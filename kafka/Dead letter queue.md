
- dùng của spring boot 
- @dltqueue
- @retryable, max số lần attempt message vào các topic retry để ko block các message khác
- ko dùng dead letter queue được khi yêu cầu thứ tự là nghiêm ngặt 
- Khi dùng @dltqueue hay @retry của spring thì phần header trong queue tự động có stacktrace exception, mình ko cần thêm thủ công 
- `attempts = "1"`: **chỉ có 1 lần xử lý message ở topic chính**, không tạo các retry topic theo cấu hình attempts. Nếu xử lý lỗi thì message sẽ đi **DLT**.
- `DltStrategy.ALWAYS_RETRY_ON_ERROR`: nếu **xử lý message trên DLT thất bại** (ví dụ DLT handler cũng throw exception), Spring Kafka sẽ **tiếp tục retry việc xử lý DLT message**.
- `DltStrategy.FAIL_ON_ERROR`: nếu **xử lý message trên DLT thất bại**, việc xử lý DLT sẽ **fail ngay**, không tiếp tục retry DLT message.


``` java 
@RetryableTopic(
  attempts = "1", 
  kafkaTemplate = "retryableTopicKafkaTemplate", 
  dltStrategy = DltStrategy.ALWAYS_RETRY_ON_ERROR)
@KafkaListener(topics = { "payments-retry-on-error-dlt"}, groupId = "payments")
public void handlePayment(
  Payment payment, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    log.info("Event on main topic={}, payload={}", topic, payment);
}

@DltHandler
public void handleDltPayment(
  Payment payment, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    log.info("Event on dlt topic={}, payload={}", topic, payment);
}

```

https://www.baeldung.com/kafka-spring-dead-letter-queue