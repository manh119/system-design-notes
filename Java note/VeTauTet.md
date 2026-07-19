

Xem mỗi lần 1h - 5h video, tự code lại trong 2h (not AI)
mỗi video chia thành : vấn đề -> giải pháp (tool) + ý tưởng -> coding 


## Init 
- spring boot là phát triển hơn của spring, giúp phát triển phần mềm nhanh hơn 
- go - dùng cloud native nhiều, dùng go xu thế 

## Lession 1

- mononithic = MVC ? 
![[Pasted image 20260718085934.png]]

- setup dự án Domain driven design 
- dependency ở pom chung, thì tất cả module con đều ăn 
- nhiều file model ở nhiều module, nếu cần dùng model chung thì cần convert mapper sang module tương ứng, ko dùng chung model. Entity thì chung 
lession 2
- Application phụ thuộc vào domain + infra, domain ko phụ thuộc ai, infra phụ thuộc domain, controller phụ thuộc application, start phụ thuộc controller 
lession 3 
- package by feature (trong ddd thì là nằm trong application) , package by layer 
![[Pasted image 20260718092911.png]]


## Lession 4 

- cơ chế bảo vệ bởi Circuit Breaker vs RateLimiter
- sentinal của alibaba (eng ver cũ)
- Resilence4j netflix, thực hành circuit breaker + rate limiter + health check 
- #todo interview question về Resilence4j và kiến trúc DDD 
lession 5 : 
- phòng thủ bằng distributed cache (Redisson - distributed lock được recommend bởi redis  https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/ )
- code 


lession 6 : 
- anh tip làm remote, tuần 4 ngày, 2h sáng -11h 
- tại sao ko dùng lua redis mà dùng redission 
	- vì lua khó controll unlock, vì redis crash, trong redis cluster khó triển khai bằng lua 
	- redission ko cần TTL ? 

lesion 7, 8 : 
- protheus : thu thập dữ liệu, lưu trữ, truy vấn, cảnh cáo 
	- import vào pom spring, setup đọc dữ liệu ở đâu 
	- add gói node exporter full, add id, xem cpu 
- grafana : trực quan phân tích dữ liệu 
- giám sát sql : mysql exporter, các thông số quan trọng trong dashboard mysql, table lock, connection pool 
- lession 10 : giám sát redis, connects 
	- Local cache, redis max connection là 10k, max connection mysql 150. Thực ra qua rate limitter hoặc add cái queue trước là ok rồi, local cache khi invaildate hơi khoai. Vì là local nên tính nhất quán dữ liệu giữa các local cache, toang :v 
- ![[Pasted image 20260719101854.png]]


## lession 11 

- redis cluster, master/slave vs sentinal xịn hơn chứ nhỉ, chỉ cần scale slave là đc :v 
- chọn thư viện triển khai local cache của google (guava thay vì jedis hoặc cafein)
lession 12 ; 
- impl local cache 
- tại sao lấy null vẫn set vào redis hoặc local cache ? 
- virtual thread 
lession 13 : 
- elk docker 
- distributed cache trước khi query vào db để đảm bảo chỉ 1 request vào lấy dữ liệu từ db ? Và các request khác phải đơij bởi redission ? 
- logback spring 
lession 14 : 
- đòng thời cao -> phải sử dụng cache
- update data cho redis, local cache phải sử dụng lock 
- dữ liệu null cũng phải lưu trong cache (đánh dấu là dữ liệu ko có trong db) - ví dụ (key_123 : null) trong redis thì nghĩa là db ko có key này chứ k phải lỗi 
lession 15 : 
- cấu hình nginx https, ssl, upstream + localCache ko consistency 
- khi mua hàng -> update local cache service A + redis nhưng ko update được service B -> inconsistency 
lession 16 + lession 17 : 
- giải quyết local cache và redis 
- cách 1 : dùng redis pub/sub lắng nghe thay đổi ở redis -> update local cache -> cồng kềnh 
- cách 2 : lưu version mỗi khi thay đổi ở redis, verison ở local cache, version ở client (app), khi gửi xuống thì check version với local cache. Nếu khác version tức client đã được lấy version mới từ redis -> update local cache. 
	- Nếu user chỉ đọc trang detail một lần -> lúc thanh toán vẫn có thể bị sai (đã được người khác update) -> cần check strong consistency chỗ này. 
- impl local cache 

### redis 

- 1. lua vs transaction trong redis 
- Multi command : chuyển connection của client thành connection có transaction 
- EXE command 