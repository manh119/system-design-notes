
# High level design 
## 1) Users should be able to start group chats with multiple participants (limit 100)

#### Dùng LB7 hay LB4 cho websocket ? 
- Dùng LB4 thôi, vì đơn giản là chỉ cần giữ kết nối websocket đến server, không cần đọc tin nhắn, hay content hay header, hay URL của http hay tầng app để load balancer (chia tải đến đúng server)
- Microservice kiểu : chat-service/api/v1/chat, group-service/api/v1/chat thì dùng kiểu LB7 nhỉ, vì nó đọc URL để chia service mà nhỉ ? -> đúng là thế
- Nếu thế thì websocket connection cũng phải chỉ được kết nối tới chat-service thôi chứ nhỉ? -> tách ra WebSocket Gateway và HTTP API Gateway

#### Luồng sẽ là 
1. User call API POST api/v1/create-chat

	- ```json 
	  {
		  memeberIds : [],
		  groupName : "ABC" 
	  }
	  -> response 
	  {
		  groupId : "123"
	  }
	  ```
2. chat service tạo mới bản ghi group_chat table, memeber_table (bọc 2 lệnh này trong 1 transaction là được)

#### Phân tích cách thiết kế bảng để phục phụ query ?
	- tất cả user trong một group chat 
	- tất cả group chat của một user 

| cách 1                                                  | cách 2                                          |
| ------------------------------------------------------- | ----------------------------------------------- |
| group_chat<br>- id<br>- name<br>- member_ids []         | group_chat<br>- id<br>- name                    |
| users<br>- id<br>- name<br>- dob<br>- group_chats_id [] | users<br>- id<br>- name<br>- dob<br>            |
|                                                         | members <br>- id<br>- userId<br>- group_chat_id |

- với cách 1, các thông tin thuộc về member như role (quyền admin), thời gian vào group, mute_until, last_read_message ko biết để vào đâu, để vào bảng users cũng ko đúng, mà bảng group_chats cũng ko đúng -> chỉ có bảng member mới đúng 

- Và đánh composite index trên bảng memeber (userId, group_chat_id) và (group_chat_id, user_id) để phục vụ 2 query trên + có thể để primary key là (group_chat_id, user_id) luôn, đỡ phải thêm cột id 

#### Điểm khác so với dynamoDB và Composite Primary Key + GSI là :

- Postgres thì nó sẽ tạo 2 cây index dựa trên 2 composite key, lúc tìm thì sẽ tìm trên 2 cây đó 

- DynamoDb thì phải chọn partition key luôn từ đầu, ví dụ group_chat_id làm partition key, user_id làm sort key. Kết hợp với Global secondary index là replica (nhân bản dữ liệu) và sắp xếp dữ liệu theo partition key là user_id và sort key là group_chat_id -> Đọc/ghi hàng tỷ dữ liệu thì vẫn thế, vì scale tuyến tính  - nhưng tốn gấp đôi dung lượng 


## 2) Users should be able to send/receive messages.

#### Giả sử chỉ cần 1 có server
- tất cả user đều online, mỗi khi user mở app thì thiết lập một websocket connection, server lưu hashmap kiểu : userId -> websocket connection ID 
	1. user A gửi message vào một group chat Id
	2. Server lưu vào db và send ack cho user A là SENT 
	3. Server query tất cả member của group chat đó và dựa vào hashmap connection để gửi message đến từng user  

#### Liệu có còn connection của http hay websocket khi app chạy nền ko? 
- Nếu ko thì tại sao firebase lại có thể bắn notification ?
- -> khi chạy nền thì hệ điều hành có thể kill bất kỳ lúc nào. Firebase gửi được vì có google play service và apple push notification service luôn có kết nối .

#### Các trạng thái của message 
- SENT - khi server ack ok cho người gửi 
-  DELIVERIED - khi thiết bị người nhận ack cho server
- READ - khi người nhận vào đọc tin nhắn 
## 3) Users should be able to receive messages sent while they are not online (up to 30 days).

#### Bảng inbox table (undelivered message table realtime) 
- Khi một người dùng gửi message, sẽ lưu message ở inbox table và message table, sau đó ack cho người dùng là SENT  
- khi gửi được message qua websocket và thiết bị người dùng ack đã nhận -> xóa message dó khỏi inbox table 
- khi user offline và online trở lại -> query tất cả message chưa đọc của người đó trong bảng inbox table và gửi cho người đó qua websocket. 
- Chắc sẽ chọn receiverId làm partition key, và messageId làm sort key, vì inbox là thuộc về các người dùng khác nhau, inbox table lưu kiểu : 
	- receiverId
	- messageId 
	- group_chat_id 
- Bảng message kiểu : 
	- messageId 
	- group_chat_id
	- sender
	- content 
- Thế bảng message thì chọn cái gì làm partition key được nhỉ? -> group_chat_id làm partition key, và timestamp làm sortkey vì các message trong các chat ko liên quan gì đến nhau. 
-   200M daily active user, 20 message/day = 4B message/ day = 4 x 10 ^  9 = 40k message/second  -> when write to message table and inbox table, we need about 100k write / second -> write vào bảng inbox và bảng message đều ok, vì có partition scale tuyến tính mà. 
#### Tại sao TCP khi mở kết nối lại là 3 way handshake 
- mà ko phải là 2 way handshake và 4 way handshake. Tại sao khi TCP đóng kết nối lại là 4 way handshake ?
- Vì các gói tin được chia nhỏ để gửi trên môi trường mạng hỗn tạp, nên cần đánh số thứ tự của từng gói tin để đảm bảo không nhận trùng, đúng thứ tự. Ví dụ nhận gói tin số 5 rồi mà chưa nhận được gói tin số 3 thì Client có thể request để yêu cầu gửi gói tin số 3 
- Và handshake là để đảm bảo bên kia còn sống và số thứ tự để bắt đầu là gì. Nên bắt buộc phải có 4 bước : 
	- 1. Client gửi số thứ tự và đợi phản hồi ack 
	- 2. Server bảo tôi đã nhận đc số thứ tự của ông rồi nhé, bắt đầu gửi từ 1000 đúng ko
	- 3. Server gửi số thứ tự để gửi của nó, bắt đầu từ 5000 nhé 
	- 4. Client ack oke nhé
- -> nhưng có thể gộp bước 2, 3 và là tcp 3 way handshake 
- Còn khi đóng kết nối cũng phải qua 4 bước : 
	- 1. Client bảo tôi gửi xong rồi nhé, client vẫn đợi server gửi xong thì mới đóng kết nối. 
	- 2. Server bảo, oke nhé 
	- 3. Server đợi gửi xong package cho client và bảo tôi gửi xong rồi nhé, nếu ko thấy phản hồi ở bước 4 thì gửi lại 
	- 4. Client bảo oke nhé và vẫn đợi 5p, server nhận được và sau đó server đóng kết nối 

#### Tại sao phải tách bảng inbox, trong khi bảng message có thể để trường status 
- bảng message có thể để status = SENT, DELIVERIED, READ nhỉ? tách bảng inbox hình như hời thừa 
- Vì inbox là riêng của từng user trong group chat, 1 tin nhắn có thể thuộc về nhiều người dùng khác nhau, và mỗi người có các trạng thái riêng, cùng 1 tin nhắn nhưng người này đã đọc và người kia chưa nhận được 
- Và nếu triển khai chức năng người dùng đã đọc một message trong một group chat chưa thì có 2 cách : 1 là thêm trường status trong bảng inbox có các trạng thái DELIVERIED, READ, hai là tao một bảng mới ReadReceipt gồm (group_chat_id, user_id, last_read_message) 
- Nên dùng bảng ReadReceipt vì đã đọc hay chưa thì nó theo group chat với từng user, chứ không phải là theo từng tin nhắn như trong bảng inbox lưu.

#### Khi người dùng từ offline sang online trở lại thì khi gửi đến device user thành công là xóa khỏi inbox table hay là khi người dùng vào đọc thì mới xóa khỏi inbox table và tại sao ?

- nên xóa luôn, vì nếu để thế thì bảng inbox phình vô hạn à :v 

#### Inbox table khác gì queue đâu nhỉ, và so sánh với cách dùng queue?
- Vì khi từ offline sang online mình có lệnh lấy tất cả tin nhắn của user B -> queue không thể query được như thế. (Random access vs queue là sequencel stream)
- + có thể lưu trữ lâu dài, để lưu 30 ngày trên queue cũng ko tốt, phình và nghẽn queue 
## 4) Users should be able to send/receive media in their messages.

#### Presigned URL 
- người dùng muốn gửi media -> query chat server để lấy presign-URL để write 
- người dùng upload trực tiếp media qua presign URL và lấy media_Id để gửi kèm message 
- chat server lưu lại và fanout service sẽ fanout message cùng presign URL đến người nhận 
- người nhận lấy cũng lấy link presign URL để đọc media trực tiếp từ s3 

#### Tại sao ảnh, video ko nên lưu ở db thông thường ?? pre-sign URL để user upload trực tiếp và read attacment được dùng ở big tech nào? dẫn chứng ?
- replica đắt, query db lâu hơn vì file nặng hơn, db thường là ssd và chi phí đắt hơn, cache của db chiếm toàn media -> ko hiệu quả. Backup, vacccum đều nặng 
- Slack : có hàm https://docs.slack.dev/reference/methods/files.getUploadURLExternal/
- Discord web, khi upload ảnh sẽ lấy upload URL, sau đó PUT ảnh đến URL đó 
- ![[Pasted image 20260613164207.png]]
- ![[Pasted image 20260613164315.png]]
#### Vấn đề lớn nhất của presign url chắc là security + handle vòng đời? 
- Có vụ s3 tiktok storage bị hack để lưu toàn video của hacker ?
- Bear (là động từ nghĩa mang, cầm) -> Bearer token (ai cầm token thì có quyền) -> khóa content type, content length, content size 
- Dùng AWS CloudFront làm proxy trước khi upload
# Deep dive 

## 1) How can we handle billions of simultaneous users?
1) How can we handle billions of simultaneous users?

bad : scale ngang (tăng thêm server) sẽ ko work, vì websocket được lưu trạng thái ở server, vậy nên cần một chỗ chung để lưu connection nào ở máy nào gắn với user nào 
-> có thể là redis, kafka, database 

bad : kafka topic per user - mỗi topic mỗi user, khi user vào app -> mở websocket connection đến server A -> server đăng ký lắng nghe topic ở kafka user A
-> khi người B gửi tin nhắn -> có fanout service để quét mọi người trong một group chat và gửi đến topic tương ứng với từng người 
-> server A lấy tin nhắn ra và gửi đến người A qua websocket 
-> nó vẫn hoạt động tốt với hàng triệu người dùng chứ nhỉ ? 
-> mỗi topic có meta data như partition, cấu hình các kiểu, rồi mỗi topic tối thiểu 1 partition, high availability thì 3 partition -> overhead mỗi topic và tạo sẵn topic cho hàng triệu người ko active cũng phí tài nguyên 

dẫn chứng Whatsapp famously served 1-2m users per host ?

thế nếu topic per partition ko ổn thì cách topic per group chat ổn ko? mỗi topic sẽ đảm nhận tin nhắn của group chat đó ?
-> ko nên thiết kế topic/partition (tĩnh) dựa vào cái động (user, group chat) 
-> có thể chia sẵn 256 partition, chỉ cần đảm bảo tin nhắn trong group gửi đến đúng partition là được bằng cách consistency hash , partition nào = hash(group_Id) % num_partition 
-> thế thì khi consumer đọc 1 message từ 1 partiton (group) thì nó query mọi người trong một nhóm và gửi hết cho mọi người đó rồi mới đánh dấu là done à? như thế có vẻ gây tắc nghẽn cho message của group chat khác nhỉ ? 
-> thế thì quá lâu -> nên tách rời để có một consumer thread đọc được message từ kafka và đánh dấu là đã đọc luôn và để việc gửi message cho worker khác 
-> thế thì có kafka vào làm gì? nếu consumer thread đọc xong mà die thì sao, mất message đến các group à ?

good solution : consistent hasing of chat server 
-> mỗi user Id cố định một server bằng hash (user ID) 
-> các service luôn biết user A ở server nào -> gửi tin nhắn trực tiếp là được 
-> nhưng sẽ gặp vấn đề khi scale thêm service vì phải tính toán lại range hash, và nếu các service nhỏ -> call API chằng chịt giữa các service 


greate solution : pub/sub redis 
kafka : disk-based, replica, append-only log, topic data overhead
redis pub/sub : lightweight, mất tin nhắn nếu người đó ko online 
-> khi người dùng gửi tin nhắn thì ghi bản ghi vào message + inbox (message chưa gửi) table 
-> đánh dấu đã gửi thành công cho user đó 
-> gửi vào redis pub/sub (best effort) 

- nếu ko gửi được cho một người nào đó do offline hoặc mất kết nối websocket thì khi người đó online trở lại thì get all message từ inbox table là được 

- pub/sub redis nên chia theo userId hay chatGroupId ? 
- nên theo userId chứ nhỉ? vì nó map uerId -> websocket connection mà nhỉ. Lúc gửi cũng gửi đến userId mà. Thì phải có lệnh query tất cả user của một group chat và gửi đến từng user một -> sẽ tăng i/o
- chatGroupID : tất cả userId có socket conection riêng đến server riêng, nhưng mà đều subcribe vào một group_chat_id nào đó và khi có tin nhắn mới trong nhóm thì tất cả connection đó đều nhận được tin nhắn. uhm cũng hợp lý mà nhỉ. -> giả sử nhóm user tham gia 200 nhóm chat (1-1) -> khi kết nối cần subcribe 200 kết nối tới redis -> càng nhiều người thì càng nhiều subcribe 

-> giải pháp hỗn hợp : vì whatpp chủ yếu là chat 1-1 -> prefer partition by user -> chỉ cần gửi message đến đúng userId 
nếu nhóm > 25 người, thì khi người dùng kết nối -> subcribe thêm vào kênh nhóm lớn đó ngoài subcribe userID đó. -> lúc 1 người gửi message, chỉ cần gửi 1 lần vào kênh nhóm, thay vì for loop để gửi đến từng user ID 


## 2) What do we do to handle multiple clients for a given user?

2) What do we do to handle multiple clients for a given user?
- bảng inbox thay vì per user thì per client là được 
- bảng client chứa các thông tin (userId, type, lastseen, deviceId, ...)
- lúc một người dùng gửi message đến một group chat thì tao message và query tất cả thiết bị của người đó để thêm vào bảng inbox 
- và nên giới hạn ví dụ 5 thiết bị tối đa. Ví dụ 1 người nào đó đăng nhập 100 thiết bị thì khi bất kỳ message nào gửi đến người đó, mình phải loop tạo bản ghi cho 100 thiết bị đó

## 3) What happens if the WebSocket connection fails?

### Không làm gì, phụ thuộc vào TCP keep alive 

- Khi connection chết, người dùng gửi tin nhắn thì sẽ không gửi được, client tạo một websocket connection mới để gửi thôi nhỉ? Còn server thì tcp keep alive nếu timeout thì đóng connection đó thôi. 

- bị vấn đề gì không ? -> server phải tốn chi phí quản lý connection ma - connection không tồn tại (server đơn giản là listen thôi, nhưng connection đó lại bị đóng mà server không biết) - hàng triệu kết nối ma thì tốn RAM + file desciptor 

- bình thường nếu user chủ động tắt wifi trong app, tắt app thì os vẫn gửi gói tin FIN/RST để đóng connection đó

### Phát hiện khi server push tin nhắn và đợi ack 

- Hình như nếu websocket fail thì client ko vấn đề gì, đơn giản là tạo connection mới thôi nhỉ?, vấn đề chỉ ở server là quản lý connection không tồn tại thôi thì phải 

- khi server push tin nhắn + retry qua websocket, nếu sau 2s không thấy ack -> chủ động đóng websocket connection đó 


### Heartbeat / Ping-Pong

- server định kỳ 30s gửi PING cho client và đợi PONG, nếu quá timeout là 10s thì server chủ động đóng connection đó. Và client cũng đếm quá 35s thì cũng chủ động đóng connection và tạo websocket connection mới

- server PING/PONG cho client thì ok hơn việc client PING/PONG cho server, vì nếu client PING/PONG thì server lại phải duy trì bộ đếm timeout cho mỗi connection ví dụ quá 30s thì đóng.

- ví dụ 200M user đồng thời, 10s gửi PING một lần thì có bị vấn đề gì không, khi đây chỉ là heartbeat (không phải xử lý bussiness) -> 20M request/s và không vấn đề gì về tải 

- nhưng mà chắc ping/pong này chỉ khi websocket mở tức khi người dùng mở app thôi nhỉ, còn nếu người dùng đóng app, thì chắc qua FCM (firebase) để push nhỉ?



## 4) What happens if Redis fails to deliver a message?

-  đơn giản nếu redis ko gửi được tin nhắn thì thôi, redis thì là best effort thôi mà, coi như websocket đó die, hay redis die, khi người dùng re-connection lại thì đồng bộ tin nhắn mới từ bảng inbox thôi - à vấn đề là nếu redis die, websocket connection vẫn còn đến server chứ ko phải case người dùng offline, thì làm sao để biết mà gửi realtime tin nhắn cho người dùng 

- hình như vấn đề chính là bảng inbox, có rất nhiều dữ liệu 
#### Client polling mỗi 30s, có tin nhắn nào mới mà tôi chưa nhận ko

- quá tải, ví dụ 200M user, poll mỗi 20s -> 10M request/s đến inbox table chỉ để đề phòng trường hợp redis chết ko gửi đc tin nhắn 

#### Đánh số thứ tự tin nhắn 

- Đánh số 1, 2, 3, 4, cho tin nhắn, nếu server push được tin nhắn 5, 6 mà client ko nhận được 1, 2 ,3 thì client chủ động query lấy tin nhắn cũ -> nhưng nếu case ko nhận đc 1,2,3 trong thời gian dài mà cũng ko nhận đc 5,6 -> vẫn phải poll của cách 1 
#### Heartbeat + đánh thứ tự toàn bộ tin nhắn của user 

- đánh thứ tự tất cả tin nhắn của một người dùng trong tất cả các nhóm, khi heartbeat của websocket thì đính kèm số thứ tự mới nhất của người đó -> luôn biết được có tin nhắn mới hay ko? 

- Vấn đề bây giờ chắc là lưu số tự tự bảng nào, và đếm như thế nào, query ở đâu? 

- -> query để gửi websocket hearbeat thì query ở redis



### 5) How do we handle out-of-order messages?

- Hoàn toàn có việc tin nhắn A gửi trước nhưng lại đến sau tin nhắn B (theo time stamp của user)

-  không cố gắng đợi để sắp xếp thứ tự ở backend, vì sẽ phải đợi xem có tin nhắn nào đến trễ nhưng timestamp theo client thì trước không (add latency) -> người dùng thà thấy tin nhắn mới, còn hơn đợi để đúng order 

- Và ví dụ client A gửi tin nhắn 1 sau đó gửi tin nhắn 2. Nhưng tất cả user khác trong group đều có thứ tự tin nhắn 2 > tin nhắn 1. -> bị ko đồng bộ. Nhưng khi client A reload lại tin nhắn của 1 group chat thì sẽ đồng bộ như vậy. 

- khi người dùng gửi tin nhắn đến server thì đóng timestamp server nhận, khi app hiển thị thì sort theo timestamp đó là được, nên tất cả client đề thấy thứ tự giống nhau. 

- Đồng bộ giờ giữa các server qua giao thức Network Time Protocol.


### 6) How can we handle a "last seen" functionality?

#### Ghi vào db mỗi khi user action 

- mỗi khi websocket hearbeat, user send, read tin nhắn -> update last seen (lần cuối online) bằng lệnh 

```
Update user_status SET last_seen = now() where user_id = XXX
```

- 200M user online -> 20M ghi mỗi giây xuống db -> ko cần thiết cho dữ liệu ko cần chính xác lắm 

#### Chỉ ghi lúc người dùng offline 

- khi websocket die, khi server heartbeat PING/PONG và check thấy websocket die -> update bảng LastSeen  

- Nhưng đây chỉ là biết được offline lúc mấy h, thế còn trạng thái online để hiển thị tick xanh thì sao ? -> khi người dùng mở chat -> thì call API check trực tiếp có còn websocket connetion qua redis pub/sub của user B hay không.

- lúc check thì call cả db để lấy last seen + check websocket connection qua redis pub/sub -> cái nào hiển thị trước thì lấy cái đó. 

- chia type trong một connection (multiplexing), type = CHAT_MESSAGE, type = LAST_SEEN, type = TYPING 














> [!NOTE]
> 20 câu hỏi interview về bài what app nếu phỏng vấn tiktok cho swe
> 1. so sánh kafka pub/sub vs redis public để gửi message 
> 2. Redis pub/sub - giải quyết bài toán fanout và nhanh (best effort), nhưng message sẽ bị mất nếu người dùng đó offline, còn một cách nữa là có hẳn một service để fanout 
> 3. 


websocket ở tầng 7 (tầng application), còn socket ở tầng bao nhiêu? và sự khác nhau giữa http keep-alive lâu và websocket là gì ? Vì http keep-alive lâu cũng gửi được message từ server đến client và client đến server như websocket thì phải 

-> socket là API của hệ điều hành để tầng ứng dụng giao tiếp mạng (TCP hay UDP)
-> TCP mặc định đã có fullduplex (2 chiều), http là mô hình rule request-response, http-keep alive là giữ lâu request-response hơn trong 1 connection.  

Có nên luôn dùng http keep alive ko? vì dùng nó thì đỡ phải thiết lập và đóng connection nhiều lần ?
-> nghe bảo là các framework đã có sẵn, ko biết đúng hay ko? demo xem :v 




