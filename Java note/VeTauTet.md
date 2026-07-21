https://github.com/manh119/VeTauTet

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
- done setup elk, grafana, promoteus for mysql, redis, node, disitributed lock


### redis 

- 1. lua vs transaction trong redis #todo question 
- sự khác nhau, khi nào dùng, ưu nhược điểm 
- Multi command : chuyển connection của client thành connection có transaction 
- EXE command 
2. trong một transaction của redis, nếu luồng khác sửa key đó 
	- -> sau cùng là nhận giá trị của key luồng khác, chứ ko phải luồng đang chạy transaction 
	- -> dùng WATCH, trong 1 transaction, nếu 1 biến bị luồng khác sửa thì sẽ hủy transaction 
3. EVAL 
	- redis.call, redis.pcall một cái thì bắn lỗi và các lệnh tiếp ko chạy đc nữa, một cái thì ko log lỗi, ko thực hiện lệnh ko chạy đc, và chạy tiếp các lệnh khác 
	- nhược điểm lua, ko rollback khi lỗi ? 
	- lua sẽ được đưa vào hàng đợi theo thứ tự -> đảm bảo tập lệnh lua ko bị gián đoạn và đảm bảo tính nguyên tử 
- 4. master slave vs sentinal 
	- master ghi + đọc, slave chỉ đọc 
	- TH master down -> ko có cơ chế tự up à ?? 
- 5. thiết lập sential, khi master down thì up một slave lên thay, 


## lession 18 

- khẩu trừ hàng tồn kho khi lượng đồng thời cao 
- vấn đề : 
	- khi nào thì trừ hàng, khi đặt hàng thành công, khi thanh toán thành công, khi giao hàng thành công ? 
	- đảm bảo 3k request per second, đảm bảo strong consistency giữa redis và mysql khi black friday 
	- ko để bán vượt số vượt kho (overselling) lỗ trong giá rẻ, vé còn nhưng báo hết (mất doanh thu) undersellling, bottleneck 
- kinh nghiệm : 
	- biết trước ngày black friday 
	- tách new service cho black friday 
	- ![[Pasted image 20260720213458.png]]
	- ![[Pasted image 20260720213608.png]]
	- double check, tách bảng blackfriday 
	- warm up redis, front end, CDN 
	- so sánh LUA vs incre, decr, get, set khi trừ hàng tồn kho 

## Lession 19 

- trừ hàng tồn ở mysql 
- dùng optimistic lock 
- dùng compare and swap 
	- UPDATE TicketDetail t SET t.updatedAt = CURRENT_TIMESTAMP, " +
            "t.stockAvailable = :oldStockAvailable - :quantity " +
            "WHERE t.id = :ticketId AND t.stockAvailable = :oldStockAvailable"

- so sánh các cách lock trong java, retranlock, compare and swap, sync, atomic integer

- lession 20 : 
	- test script level 0 query ra xong set vào, hàng tồn bị âm vì race condition 
	- cách compare and swap thì được 1k3 request per second, cách level 1 thì bị vấn đề idempotent đúng ko nhỉ? được 1k request per second. 
	- còn ví dụ 10k request perscond thì phải trừ hàng tồn trong redis dùng lua script cho atomic. Chứ dùng mysql là toang db :v 
- lession 21: 
	- check trong redis, trừ hàng tồn trong redis, thành công thì update trong db 
	- lỗi race condition : lấy từ redis, rồi một lệnh update redis -> race -> bị sai 
	- dùng lua scrip để gom get và set lại thành một lệnh -> đảm bảo atomic + consistency giữa redis và mysql. Tested với jmeter với 128k requet 
- lession 22 : 
	- 1 ngày = 5k ticket x 100 chỗ = 500k ticket -> 1 năm = 180 triệu ticket 
	- partiion thì sao , sharding thì khi count, khi avg thì phải biết table nào, rất loằng ngoằng :v
	- mỗi tháng tạo một bảng riêng biệt 
- lession 23: 
	- mã oderid của shoppe có nghi ngày tháng trên đó, ví dụ 260414abcxyz, để biết nó thuộc table nào 