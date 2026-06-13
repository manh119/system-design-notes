
# High level design 
## 1) Users should be able to start group chats with multiple participants (limit 100)

- Dùng LB7 hay LB4 cho websocket ? Dùng LB4 thôi, vì đơn giản là chỉ cần giữ kết nối websocket đến server, không cần đọc tin nhắn, hay content hay header, hay URL của http hay tầng app để load balancer (chia tải đến đúng server)

- Ơ hình như microservice kiểu : chat-service/api/v1/chat, group-service/api/v1/chat thì dùng kiểu LB7 nhỉ, vì nó đọc URL để chia service mà nhỉ ?

- Luồng sẽ là :
	1. User API POST api/v1/create-chat
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
	1. chat service tạo mới bản ghi group_chat table, memeber_table (bọc 2 lệnh này trong 1 transaction là được)


- Phân tich cách thiết kế bảng để phục phụ query ?
	- tất cả user trong một group chat 
	- tất cả group chat của một user 

| cách 1                                                  | cách 2                                          |
| ------------------------------------------------------- | ----------------------------------------------- |
| group_chat<br>- id<br>- name<br>- member_ids []         | group_chat<br>- id<br>- name                    |
| users<br>- id<br>- name<br>- dob<br>- group_chats_id [] | users<br>- id<br>- name<br>- dob<br>            |
|                                                         | members <br>- id<br>- userId<br>- group_chat_id |

- với cách 1, các thông tin thuộc về member như role (quyền admin), thời gian vào group, mute_until, last_read_message ko biết để vào đâu, để vào bảng users cũng ko đúng, mà bảng group_chats cũng ko đúng -> chỉ có bảng member mới đúng 


## 2) Users should be able to send/receive messages.

-> giả sử chỉ 1 có server, tất cả user đều online, mỗi khi user mở app thì thiết lập một websocket connection 
server lưu hashmap kiểu : userId -> websocket connection ID 
- user A gửi message vào một group chat Id
- Server lưu vào db và send ack cho user A là SENT 
- Server query tất cả member của group chat đó và dựa vào hashmap connection để gửi message đến từng user  
liệu có còn connection của http hay websocket khi app chạy nền ko? Nếu ko thì tại sao firebase lại có thể bắn notification ?
-> khi chạy nền thì hệ điều hành có thể kill bất kỳ lúc nào. Firebase gửi được vì có google play service và apple push notification service luôn có kết nối .

có các trạng thái SENT - khi server ack ok cho người gửi 
DELIVERIED - khi thiết bị người nhận ack cho server
READ - khi người nhận vào đọc tin nhắn 
## 3) Users should be able to receive messages sent while they are not online (up to 30 days).

3) Users should be able to receive messages sent while they are not online (up to 30 days).
- tạo mới bảng inbox table (undelivered message table) 
- khi gửi được message qua websocket và người dùng ack -> xóa message dó khỏi inbox table 
-> khi user offline và online trở lại -> query tất cả message chưa đọc của người đó trong bảng inbox table và gửi cho người đó qua websocket. 

inbox table lưu kiểu : 
- receiverId
- messageId 

- tại sao lại là 3 way handshake mà ko phải là 2 way handshake và 4 way handshake. Tại sao khi đóng lại là 4 way handshake ?

- khi người dùng từ offline sang online trở lại thì khi gửi đến device user thành công là xóa khỏi inbox table hay là khi người dùng vào đọc thì mới xóa khỏi inbox table và tại sao ?

- inbox table khác gì queue đâu nhỉ?


## 4) Users should be able to send/receive media in their messages.

4) Users should be able to send/receive media in their messages.

- người dùng muốn gửi media -> query chat server để lấy presign-URL để write 
- người dùng upload trực tiếp media qua presign URL và lấy media Id để gửi kèm message 
- chat server lưu lại và fanout message cùng presign URL đến người nhận 
- người nhận lấy cũng lấy link presign URL để đọc media trực tiếp từ s3 

tại sao ảnh, video ko nên lưu ở db thông thường ?? và pre-sign URL để upload và read attacment được dùng ở big tech nào? dẫn chứng ?
-> replica đắt, query db lâu hơn vì file nặng hơn, db thường là ssd và chi phí đắt hơn, cache của db chiếm toàn media -> ko hiệu quả. Backup, vacccum đều nặng 

vấn đề lớn nhất của presign url chắc là security + handle vòng đời? Có vụ s3 tiktok storage bị hack để lưu toàn video của hacker ?
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




