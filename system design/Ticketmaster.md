# Requirement

- view events
- book ticket
- search event

# Non-functional

- low latency for view (< 200ms), for search (<500ms)
- consistency for booking (no duplicate booking) 
- read heavy (100M user), high throughput for booking 10M user for an event

# Entity

- Events
- Users
- Tickets 
- Bookings

### Tại sao cần bookings table nhỉ? 

- vì table tickets có thể chứa status (available, hold, booked) và ai đặt (userID) 
- tách vì có thể một người đặt một nhóm các ticket, và total price, rồi transaction payment một lần cho nhóm ticket đó -> booking là một lần đặt như vậy 

### database per service

- là chia nhỏ để giảm sự phụ thuộc, nhưng nếu dữ liệu được gắn chặt với nhau, ví dụ ticket, booking, event liên quan chặt đến nhau
- và có các service thì nên để event service, user service, ticket service dùng chung một db thì sẽ dễ triển khai ACID hơn là ACID giữa các db trong microservice 
- dẫn chứng amazon có guildline "Shared-database-per-service pattern"
- với mục đích của chúng ta thì em sử dụng single database, nhưng trong project thực tế, em sẽ đánh giá trade off giữa sự phức tạp (ACID giữa các bảng, bảng event lưu id của user, bảng ticket lưu id của bảng event, bảng booking lưu id của bảng ticket) và khả năng mở rộng (mỗi service sở hữu riêng một database) 

# Deep dive 

## Cách tăng trải nghiệm người dùng bằng cách cho phép đăt chỗ vé để thanh toán 

- (ví dụ thanh toán các kiểu xong thì người khác lấy mất vé, vì người khác nhập thông tin thanh toán và thao tác nhanh hơn, mặc dù đã vào trang đặt vé) 
- không thể xảy ra trường hợp này, vì lúc chọn vé để đặt, thì bọc tạo mới bookingId + set status ticket = inprogress trong 1 transaction -> chỉ một người set trạng thái thành công và vào trang payment detail thành công, còn lại là lỗi "vé đang được đặt bởi người khác" 
- để thời gian giới hạn cho người dùng đặt vé, checkout (trả tiền) 

### bad solution

- lock row đó trong 5 phút, để TTL ở tầng app, 5 phút đó bọc trong một transaction gồm đặt vé, thanh toán, ...
- rủi ro lock mãi row đó nếu service die + transaction 5 phút là quá dài là bottleneck về performance khi người dùng tăng 

### good solution

- lưu status = REVERSED ở database + có trường exprired_at + cronjob quét 1 phút một lần để tìm những booking đã hết hạn để đổi status thành AVAILABLE  (AVAILABLE, REVERSED, BOOKED) 
-  thực ra mình thấy bị lag giữa vé đã hết hạn nhưng phải đợi cronjob quét thì ko vấn đề gì lắm, vấn đề là bảng ticket nhiều dữ liệu, phải quét toàn bộ bảng thôi (ơ nhưng mà có thể giải quyết bằng cách sau 1 ngày thì dời tất cả những vé đã được đặt (BOOKED) sang history là được mà :)) 

### greate solution 1

- giải quyết vấn đề lag của conjob (vé đã timeout nhưng phải đợi cronjob chạy) bằng cách khi một người nhấn đặt một vé thì check trạng thái cho phép một người dùng đặt vé bằng AVAILABLE, hoặc REVERSED + expired 
- FE vẫn hiển thị thời gian đếm, + khi người dùng thanh toán xong thì check expired xem đã hết hạn, nếu rồi thì hoàn tiền cho người dùng ? 

### greate solution 2

- TTL with redis, status = AVAILABLE, BOOKED, khi người dùng đặt thì check xem ticket đó đã có ai đang đặt chưa ở trong redis (key = userId + ticketId) + set TTL 
- nếu chưa thì set key và lúc thanh toán cũng check như vậy để xem quá hạn thanh toán chưa 
- giảm tải việc check cho database cực nhiều, vì có nhiều người cùng ấn vào vé để cố gắng đặt 
- nếu bị edge case, người dùng nhấn thanh toán, và trong lúc thanh toán thì hết hạn TTL -> có thể giải quyết bằng hoàn tiền, hoặc mỗi bước đều check sắp hết hạn TTL chưa, và ví dụ đến bước nhấn nút thanh toán thì có thể gia hạn thêm TTL cho người dùng
- nếu redis sập thì sao ? -> dùng redis cluster, thế nếu redis cluster sập thì sao ? -> thực tế thì tạo mới instance redis sau khi sập khá nhanh -> sẽ có trường hợp nhiều user đặt cùng một ticket, nhưng ở trong db mình đã có optimistic lock update where status = AVAILABLE and ticket id = xxx -> những người update sau sẽ bị lỗi thôi 

#### câu hỏi chắc là lưu thời gian đặt chỗ (timeout) của người dùng ở đâu (service, redis) 

- 2 chỗ này đều có thể die, và mất đặt chỗ của người dùng  
- và khi service hay redis sống lại thì người khác sẽ lấy mất vé mặc dù đã thanh toán (ví dụ đang thanh toán thì service lưu trạng thái bị die), và thanh toán xong thì mất trạng thái đặt chỗ của vé đó  
- có thể lưu thời gian timeout để reversed ở 3 chỗ : service, redis, database  
#### service 

- phải lưu thêm trạng thái REVERSED của row đó trong db để người dùng khác còn biết nó đang được đặt. Nhưng nếu service die thì mất trạng thái và để vé ở trạng thái REVERSED mãi.  
#### nếu lưu status = REVERSED ở database + có trường exprired_at  

- giải quyết TH nếu service die thì mất trạng thái và để vé ở trạng thái REVERSED mãi bằng cách
- cronjob quét 1 phút một lần để tìm những booking đã hết hạn để đổi status thành NEW -> cách này cũng khá ok, nhưng phải quét toàn bảng database (khá nhiều bản ghi + batch update) khá nặng  
- câu hỏi đặt ra là dùng lock loại gì, optimistic lock hay pestimistic lock, isolation level là bao nhiêu. và bọc hành động gì trong transaction ?  

#### tại sao db lại ko thích transaction dài 5 phút ?

- mà thực ra là chỉ thay đổi (commit hoặc rollback), giữ state của một row nhỉ, có vấn đề gì đâu nhỉ ?  
- hết connection (thread), ko thể xử lý request khác  
- nếu một row đang trong transaction, hay một row bị lock, thì các request khác khi truy cập sẽ nằm trong hàng chờ, hay là bị throw lỗi luôn nhỉ?  
- nếu throw lỗi luôn cũng ok, có thể báo sớm cho người dùng vé này đang được đặt bỏi người khác sớm cho người dùng

## 2. cách để handle nhiều view event (hàng triệu view) ? 

- cache thôi, vì event cũng ít khi thay đổi khi đã tạo, dễ :)) 
- invalidate cache khi db thay đổi, ví dụ ngày công chiếu event, thay đổi người biểu diễn 
- service thì tăng service là được, dễ, vì service là stateless mà 

## 3. cách để đảm bảo trải nghiệm người dùng khi mà hàng triệu người đặt event cùng lúc 

- ví dụ check vào seat nhưng mà báo seat đang được đặt -> khó chịu (mất công 1 click) 
- báo realtime cho người dùng luôn, biết ghế nào đang được giữ chỗ -> khi vừa click thì báo đỏ ngay, xanh đỏ đau hết mắt :)) 
- virtutal queue (thay vì giải quyết vấn đề nhiều người dùng, thì cho vào queue để bắt họ đợi :)) 
- ví dụ chỉ cho phép 500 người dùng vào để chọn ticket trong một khoảng thời gian nào đó + SSE để thông báo vị trí hiện tại của người dùng, sắp đến lượt đặt vé chưa :v 

## 4. Cải thiện search 

- Search bằng like và kết hợp search nhiều trường trong SQL -> ko hiệu quả  
- dùng tính năng full text index của postgres (đòng bộ nhanh, cũng là inverted index) 
- nếu chỉ search 1 field đơn giản -> dùng extension pg_trgm (full text index) trong postgres vẫn đáp ứng được chục triệu bản ghi 
- greate solution : elastic search khi cần ranking, highlight, full text search, fuzzy search, auto complete, từ đồng nghĩa, shard -> nên search được trên hàng tỷ dữ liệu  (search gần đúng) 

#### lệnh sql like luôn yêu cầu full table scan à ?? 

- ko, nếu like "abc%" thì vẫn ăn index, vì index của text là sắp xếp từ mà :)) 

#### vấn đề : làm sao để dữ liệu postgree đồng bộ với dữ liệu của elastic search 

- dùng CDC (change data capture)
- CDC có làm giảm hiệu năng của postgres đáng kể ko? so sánh với cách dùng cronjob và update vào elastic search theo batch ?  
- tốt hơn so với cronjob, ví dụ bảng 10M dữ liệu, chỉ thay đổi 100 row -> cronjob quét theo index (Poll - hỏi xem có gì mới ko), còn CDC đọc từ WAL thì chỉ biết được 100 row thay đổi đó 

## 5. giảm tải với search lặp lại thường xuyên 

- good solution : dùng cache, hình như fb dùng cache để cache, vì mình search luôn thấy kết quả cũ :)) gà vl , nhưng cần chọn TTL + trigger invalidate cache hiệu quả ko thì bị outdate với dữ liệu từ elastic search
- greate solution : elastic search có sẵn cache trong nó rồi :v 

##### Thiết kế API của youtube có danh từ ở API ko ? 

- studio.youtube.com/youtube/v1/upload/feedback?alt=json -> có méo đâu :)) 