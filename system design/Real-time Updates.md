
# Problem 

- Mô hình client-server : client hỏi, server trả lời 
- Server cần update một event cho client như chat application, uber, collaboration google docs
- 10+ year of exprience nhưng chưa có cơ hội build, vì thường do một team infra nhỏ build

# Solution 

two hop 
- step 1 : websocket, sse, long polling ()
- step 2 : poll check db or redis, message queue, pub/sub 

![[Pasted image 20260628103019.png]]

## Client-Server Connection Protocols

### Networking 101

- Mỗi tầng build abstraction của tầng dưới (ko cần quan tâm tầm dưới được build như thế nào)
- Network layer (layer 3) : 
	- handle routing (tìm đường đi) and address, chia nhỏ gói tin, cố gắng nhất (best-effort) để đưa gói tin đến một IP address. 
	- Ko giải quyết mất, trùng lặp, đúng thứ tự gói tin 
- Transport layer (layer 4) : 
	- TCP : connection-oriented protocol (oriented - định hướng, lấy connection làm trung tâm), phải thiết lập kết nối trước khi truyền dữ liệu, đảm bảo gói tin truyền đến, đúng thứ tự, ko bị mất gói tin 
	- UDP : connection less protocol - spray and pray (phun, bắn gói tin và cầu nguyện), thà bị mất 1-2 gói tin, khung hình, còn hơn đợi gửi lại đủ gói tin. 
	- TCP phải đợi đủ bao nhiêu gói tin mới tiếp tục xử lý tiếp. Nếu gói tin số 5 không đến thì đợi mãi à?
		- nếu gói tin 6, 7, 8 đến trước thì để vào buffer 
		- Khi nhận được gói 6,7,8 thì ack tôi vẫn chưa nhận được gói 5 
		- Nếu gói 5 số 5 bị mất vĩnh viễn -> trả về lỗi ERR_CONNECTION_TIMED_OUT cho người dùng 
- Application layer (layer 7) : DNS, websoket, HTTP, WebRTC (Web realtime communication)

### Request lifecycle 

![[Pasted image 20260628152243.png]]

Các bước khi gõ google.com vào trình duyệt : 
1. Phân giải tên miền thành địa chỉ IP (DNS - domain name system)
2. Thiết lập connection, 3 way handshake 
3. Client gửi HTTP request, ví dụ GET 
4. Server processing -> HTTP response 
5. Đóng connection (TCP 4-way teardown)
Note : 
- 1 round trip = 1 lần gửi và nhận -> tăng latency -> http keep alive 
- Mỗi connection TCP là một state mà client và server phải duy trì 

### With Load Balancers

Layer 4 loadbancer 
- Chia tải dựa trên IP + port - nhanh và hiệu năng cao. Phù hợp cho setup websocket 
- Giữ persistent TCP connection giữa client và server 
- Có thể coi kết nối qua layer 4 load balancer là vô hình, là kết nối 1-1 trực tiếp giữa client và server, vì lb4 chỉ đơn giản là forward và sửa địa chỉ ip đến server.
- Lúc này server xử lý trực tiếp hàng ngàn connection của client. Ko phải như loadbalancer 7

Layer 7 loadbalancer 
- Chia tải đến service (routing) dựa vào thông tin đọc được ở tầng app, như URL, cookies, content. 
- khi call request đến load balancer 7, thì nó đóng luôn kết nối đó và tạo mới kết nối đến server, có thể multiplexing và chỉ cần 100 kết nối http keep alive từ load balancer đến server 

### Simple polling, the baseline 

Ưu điểm : 
- dễ viết code + giải thích 
- stateless 
- phù hợp ứng dụng chấp nhận 2-5s latency như leaderboard,  tracke order status, youtube check upload status vẫn dùng polling 5s một lần - đã tải lên 28%)
- ![[Pasted image 20260628171025.png]]

Nhược điểm : 
- Tốn băng thông + tài nguyên kể cả khi server ko update (hỏi 2s một lần)

Chiến thuật : 
- I'm going to start with a simple polling approach so I can focus on X, but we can switch to something more sophisticated if we need to later.
- kết hợp polling + http keep alive 

### Long polling, easy solution 

- Ưu điểm là ko phải thiết lập new connection, http handshake nhiều lần như simple polling + độ trễ thấp hơn simple polling + dễ triển khai 
- Ơ giống cái api /check-status để check trạng thái thẩm định của luồng trả góp thế :)), là có một thread giữ luồng để liên tục check database xem đã chuyển sang trạng thái CANCEL hoặc SIGN_CONTRACT chưa. Giữ luồng trong tối đa 25s, vì timeout của FE là 30s. 
- Nhược điểm 
	- chắc là tốn thread pool của tomcat server ? -> có cách dùng DeferredResult - non-blocking thì giải phóng được tomcat server, và vẫn giữ thread server và kết nối mạng
	- Can be resource-intensive with many clients













### SSE - Server side event, the efficient one way street

- Thử triển khai sse đơn giản xem nào ? nó là mô hình pub/sub ở redis nếu dữ liệu là từ kafka nhỉ? Nếu dữ liệu từ kafka thì còn cách khác ko? 