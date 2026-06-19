# buổi 1, 2, 3 : kafka producer, consumer, + truyền message chạy được 

- ByteBuffer req = ByteBuffer.wrap(payload); -> bọc (chỉ là view, ko copy dữ liệu) để dùng các hàm của byteBuffer dễ dùng hơn (code sạch hơn) 
- payload là [] byte 
- short apiKey = req.getShort(); -> tự động lấy 2byte đầu, tăng position 
- . flip() làm gì (bản chất thật) resp.flip(); WRITE → WRITE → WRITE → flip → READ ?? 
- limit = position
- position = 0
- ByteBuffer không có “mode” rõ ràng,
- mà bạn phải tự quản lý bằng: position + limit



# Buổi 4 

- impl queue (ring buffer), topic 
# Buổi 5 - 11/4

### Consumer group - message consumption 

- impl consumer group gửi đúng message đến một consumer
- consumer group consumer message từ topic 
- design 1 : -> nếu mỗi consumer group consume từ một queue riêng của nó của một topic -> khi có message mới đến một topic, cần phải duplicate message ra tất cả các queue khác
- design 2 : offset - mỗi consumer có một offset 

### khi nào thì pop message khỏi queue ? 

- dừng hệ thống (stop the world) và pop message ko cần nữa (lấy min offset, pop khỏi queue và giảm offset của consumer group tương ứng) 
- topic bao gồm queue chứa message, list consumer group (support stop and pop)
- mỗi consumer group có thể dùng thread riêng để xử lý, ko ảnh hưởng đến consumer group khác 
- stop và pop có thể thực hiện khi message quá một size nào đó hoặc 5s một lần  

### cách gửi message đến consumer ? 

- consumer đăng ký port + consumer group Id đến broker 
- consumer sinh 1 thread riêng, event loop để nhận message 

### tại sao cần consumer group, khi chỉ cần producer, broker và consumer ?

- ví dụ topic A có dữ liệu [1,2,3,4] và 2 consumer A, B. Thì consumer nào đọc dữ liệu nào ? 
- nếu đều đọc hết message -> là mô hình pub/sub, ko phù hợp kiểu work queue, nhiều consumer nhưng xử lý message 1 lần 
- nếu chia cho consumer A đọc [1,2], consumer B đọc [3,4] -> cần tracking consumer sống hay chết, + lưu trạng thái đọc đến đâu rồi (offset). -> consumer group = điều phối của consumer, ông nào đọc partiton nào, đọc đến đâu rồi 

# Buổi 6

### pull-based and partition, consume message without blockage 

- hiện tại, broker check consumer nào đang ready mỗi khi cần gửi message -> gửi message cho consumer đó -> ack khi xử lý xong 
- nhược là check xem ready again after push 
- cách khác là khi consumer đăng ký -> mở 1 thread để consumer chủ động báo tôi ready vào thread đó -> thread đó update ready consumer queue, broker duy trì queue consumer sẵn sàng nhận message, 
- khi consumer nhận xong message thì báo ack (= ready để nhận tiếp) 
- broker chỉ cần quét queue ready để gửi message thôi, nhược điểm là tốn thread cho mỗi consumer (ko nhược điểm lắm nhỉ :)) 

### pull-based consumption 

- consumer lấy dữ liệu trực tiếp từ hàng đợi 
- mỗi consumer sẽ lock queue (vì message ghi vào queue, nhiều consumer group đọc từ queue), đọc message tại offset, tăng offset và giải phóng lock. -> high contention vì nhiều consumer cùng lock một queue 
- partition, chia topic queue to thành nhiều partition để tránh lock contention 

# Buổi 7

### Message persistent

- học cách lưu message xuống đĩa và làm việc với file system hiệu quả 

### State persistent 

- các thành phần : producer, consumer, topic, partition, consumer group 
- Khi server die mình muốn phục hồi, producer và consumer là TCP connection nên chỉ cần retry connect  
- Còn topic, partition, consumer group và message in the queue và queue state. ý tưởng đơn giản là lưu state xuống disk mỗi một giây là được nhỉ ? 

- Topic : Có topic id, fallback MQ, list of consumer group 
- Thay đổi khi thêm consumer group, thêm messge vào fallback MQ 
- cần lưu topic id, topic name, file name của fallbackMQ, list file name của consumer group 

- Consumer group : consumer group id, list partition 
+ thay đổi khi add thêm partition, chỉ xảy ra khi add thêm consumer (? là sao)
+ cần lưu list partition file name 

- Partition : fixed size MQ 
- thay đổi khi thêm message, hoặc khi đọc 1 message (tại sao lại là khi đọc nhỉ ? vì đọc chỉ thay đổi offset thôi mà, hay cần lưu thêm cả offset nữa)
- là thành phần hay thay đổi nhất -> làm sao để read + write xuống disk nhanh nhất  
- chỉ đơn giản là Array, cách để read/write array ? 

### Disk operation 

+ read/write file cũng chỉ cần offset (where) và count (bao nhiêu byte) = đọc / ghi array (offset tại một array = index * element size - ví dụ int là 4 bytes, ...) 
+ open, read, write, close 1 file qua fd 
+ file cũng chỉ là một byte array khổng lồ -> thao tác trên file giống thao tác trên memory

### Memory map 

+ ý tưởng : map file vào virtual memory 
+ thông thường : disk -> kernel space -> user space -> để đọc / ghi thì phải copy qua lại buffer 
+ sycn dữ liệu từ virtual memory xuống disk 
+ msync : sync một file với memory map - câu hỏi là khi nào thì sync 
+ 3 chiến lược sync : để OS tự quyết (nhanh nhất) , sync sau mỗi lần write (an toàn dữ liệu nhất, nhưng chậm nhất), sycn sau mỗi một giây hoặc 100ms 

fall back MQ khi chưa có partition nhưng có message 

có vẻ liên quan đến append-only log, kiểu chỉ đọc và ghi ở cuối file cho nhanh ? 

tại sao gọi là virtutal memory 

page vs dirty page là gì ? 