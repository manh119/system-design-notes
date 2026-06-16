
# Requirements

## Functional

- Người dùng có thể nhập điểm đi và điểm đến và xem được giá ước tính 
- Người dùng có thể đặt một cuốc xe dựa vào giá ước tính 
- Tài xế có thể chấp nhận / từ chối một quốc xe 
- 100M daily active user, 20M daily active driver 

## Non-Functional

- High availability even in the peak hour 
- Driver and Rider can see each other location in near real time in the trip (< 200ms)
- Strong consistency - gurantee no two drivers match with a rider or two rider match with a driver 
- Low latency for the matching process 

# Core Entities

- Rider 
- Driver
- Trip 
- Fare (Estimated price, estimated time,...)

# API Design

- Driver can enter their location and destination and get estimated price 
- POST : /api/v1/trips/price 
``` json 
// request
{
	"currentLocation" : {}, 
	"destationLocation" : {}	
}
// response 
{
	"estimatedPrice" : 123,
	"estimatedTripId" : 123
}
```

-  Rider can request for a trip based on estimated price
- POST : /api/v1/trips/booking
  ```json
  // request
{
	"estimatedTripId" : 123	
}
// response : Http code 200
{
	"bookingId" : 123, 
	"driverInfo" : {},  
}
  ```

- Driver can accept/decline for a request of trip
- POST /api/v1/trips/drivers-action
```json
 // request
{
	"estimatedTripId" : 123	
	"action" : ACCEPT  |  DECLINE
}
// response : Http code 200
```

- Rider and Driver can report their location
- POST /api/v1/drivers/location
- POST /api/v1/riders/location

# High-Level Design

## How would you give users a estimated fare based on their start location and destination?

![[Pasted image 20260614075243.png]]

API gateway and load balancer : cross cutting concern like authen, routing, rate limiter
Third party service (google map) for get location, mapping, distance

Rider request flow would be:
1. Rider enter destination and app get the current location and click get estimated fare in the app
2. Trip service estimate price based on location, peak hour, price model ... by using some Thrid party service like google map, and create a new record in Estimated_trip table
3. Return estimated_id, estimated_price, estimated_time to the rider

## How will riders be able to request a ride based on the estimated fare?

![[Pasted image 20260614081000.png]]

Rider flow would be:
1. Rider click the estimated trip and book for a trip
2. Booking service create new record on bookings table with status = REQUESTED
3. Booking service send notification to the rider and wait for an accept
4. Rider accept the trip, and booking serice change of record in the bookings table = ON_GOING
5. Booking service return the Booking to the rider

## How does your system match riders to the best driver for their ride?

![[Pasted image 20260614082229.png]]

The flow would be : 
1. Drivers report their current location each 10s, and booking service save current driver's location to the Drivers table
2. When booking service looking for an driver, it query all AVAILABLE driver, and caculate a score base on current driver location, rating, ... and get the driver having highest score to match for that trip

## How does your system notify matched drivers and allow them to accept/decline rides?

- Identify a list of top drivers based on their score
- The system can notify matched drivers through FCM (Firebase cloud message) or something like that in IOS (APNs - apple push notification service). And wait 1 minute timeout for this driver to accept/decline before we move the another driver.
- Driver can accept/decline for a trip through http API

# Deep Dives

## How can you handle the high write throughput from drivers sending location updates every couple seconds and efficiently perform proximity searches for matching?

![[Pasted image 20260614091302.png]]

- With 20M daily active driver, and drivers report their current location each 10s. We have 20M x 10 ^ 5 / 10s request per day = 2M report location per second 
- Assume we partition our service by city, so the peak would be 1M report location per second
- No database can handle this write request + very expensive (1.25$ for 1M write request unit 1Kb -> more than 100k $ per day) + bad query nearby location (because we need to scan full table to find nearby driver to a rider)

##### Good solution 

- batch write each 30s + a specialized geospatial database for index driver location (quad-tree). But still a lot of write + delay in batch processing.

##### Greate solution 

- we can use redis to save this information - GEOHASH + handle milion of write per second (100k-500k write per node + redis cluster)
- When finding nearby location, we can use built-in function of GEOHASH in redis for efficiently
- Run cronjob each 1 minute to remove driver go offline 
- Nếu dùng redis cluster thì phải xử lý case không thể thực hiện GEOSEARCH all node redis được -> phải chia theo thành phố + nếu gộp thì dùng GEOSEARCH ở tầng application để search nhiều node  
- What if redis go down ? 
	- Use redis persistent (RDB - fork new process to save data in to disk (effect overall performance))
	- Redis Sentinel (so sánh vs redis cluster ?) để đảm bảo high availability 
	- Because driver update their location each 5s, so if redis go down and we lose all data, we can rebuild driver's location quickly.

## How would you partition and query Redis geospatial data near city boundaries so that a rider close to the edge still matches with nearby drivers in adjacent partitions?

- we can query both adjacent city when location of drivers is nearby the boundary of the city

## How can we manage system overload from frequent driver location updates while ensuring location accuracy?

- Adaptive location update intervals : thay vì đặt cố định là 5s một lần, thì dựa vào các sensor ở điện thoại, tốc độ của tài xế, hướng đi (đi thẳng 10km), khu vực đông đúc để xác định gửi vị trí tài xế 10s một lần hay 2s một lần. 

## How do we guarantee each driver receives at most one ride request at a time?

#### Good solution 

- lock row đó tầng Database (chuyển status từ AVAILABLE -> OUTSTANDING_REQUEST) + đếm timeout trong 10s ở tầng application. Nếu hết timeout thì tự chuyển về AVAILABLE. -> lúc query để tìm tài xế phù hợp cho một rider khác thì chỉ query tài xế có status = AVAILABLE 
- Nhưng vẫn bị case tầng app chết -> tài xế bị lock mãi ở trạng thái OUTSTANDING_REQUEST -> cron job chạy 5p một lần để gỡ 

#### Greate solution 

 - When we send driver a ride request, we can keep distributed lock an distributed lock in redis with TTL = 10 second (time for driver to accept / decline a trip). And release lock when timeout or driver accept/decline a trip.
- The next time we want to send ride request to a driver, we will check if they have lock in redis, so if have, we will go to another drivers.  
- Thử thách : Nếu redis chết thì sao ??? 
	- Có node redis replica có dữ liệu sao chép từ master sẽ lên thay nếu triển khai mô hình redis cluster
	- TH mất hết dữ liệu ở redis, request A đến tài xế A, trong lúc đó redis chết và phục hồi lại mà mất hết lock -> request B check lock thì ko có lock -> gửi tiếp request cho tài xế A -> bị lỗi 2 request gửi đến 1 tài xế trong 1 khoảng thời gian. 
	- -> chốt chặn ở tầng db, vì lúc tài xế accept cần cập nhật bản ghi booking hay trạng thái bản ghi driver kiểu (khóa lạc quan)
		- UPDATE drivers SET status = ON_GOING where driver_id = '123' and status = available 
	- Nếu 0 row bị thay đổi -> bắn lỗi cho drivers và để tự động điều hướng cho user tìm tài xế khác 

## How would you implement the Redis lock safely so that only the service instance that acquired the lock can release it when the driver responds?

- Why do we need only the service instance that acquired the lock can release it? It is distributed lock, so any instance can lock or release it.
- It is edge case : when service A aquire a lock A, and TTL is 1 minute. 
- Imagine this scenario: Instance A acquires a lock for Driver X with a 1-minute TTL. Due to a slow network or GC pause, Instance A takes longer than 1 minute to process. The TTL expires, the lock is automatically released, and Instance B acquires a new lock for Driver X. 
- Now Instance A finishes its work and calls DEL on the Redis key — it just deleted Instance B's lock, not its own. Driver X is now unprotected and could receive a second ride request. The fix is straightforward: when acquiring the lock, store a unique token (e.g., a UUID) as the lock's value. 
- When releasing, use a Lua script to atomically check that the stored value matches your token before deleting — this guarantees only the original acquirer can release it. This is actually the standard Redis Redlock pattern recommendation. -> là sao ???? #todo 


## How can we ensure no ride requests are dropped during peak demand periods?

![[Pasted image 20260614094920.png]]

- Nên đặt kafka trước hay sau booking service -> phải đặt sau, vì user call HTTP request -> cần service để forward từ HTTP call vào kafka 
- When user booking, we have HTTP API call to produce serivce, this service only forward this request to kafka and gerenate a job id, and return this job id for the users. 
	- And the user can call API check-status for this job id each 5s. (Hình như lý do không giữ luồng để đợi là bởi vì có thể timeout, và ví dụ 200 request giữ luồng thì sẽ tốn tomcat thread nên ko phục vụ được request khác)
	- Hoặc khi app đang mở thì mở kết nối SSE hoặc Websocket để khi nào có matching thì gửi qua đường đó để app chuyển màn 
	- Nếu app đã tắt nhưng mở mạng thì chỉ có cách bắn qua notification qua FCM hoặc APN
	

- So Kakfa can ingest any peak traffic to ensure no ride request are dropped, and we can scale booking service base on length of kafka queue. 
- commit offset only after we successfully found a match -> service die, we still have message in queue for another instance to process 

- we could have requests that are stuck behind a request that is taking long time to process
	- Có thể vì trong 1 partition thì giống như append only log -> đảm bảo thứ tự -> nên phải đợi 1 message được xử lý xong mới được xử lý message tiếp theo
	- Kafka có cơ chế xử lý song song qua partition -> chia request xử lý lâu và request xử lý nhanh ở các topic hay các partition khác nhau 

![[Pasted image 20260616220418.png]]

## How would you implement consumer acknowledgment and retries in Kafka so a ride request is not lost if Booking Service crashes halfway through matching?
#todo 


## What happens if a driver fails to respond in a timely manner?

- Sau khi match xong và gửi request cho driver để accept / decline + lock trong redis để đảm bảo 1 request chỉ gửi đến 1 driver. Thì nếu hết hạn thời gian, thì xử lý thế nào để gửi sang driver tiếp theo ? Là bài toán Human-in-the-loop Multi-step Process.
- Nếu đếm thời gian ở tầng service -> nếu service chết -> lúc sống lại thì vẫn đọc request từ kafka và xử lý tiếp... Vẫn ok....
- Nếu check 1s một lần trong redis key của driver đó thì sao ? mỗi tài xế đợi đơn cần một cái poll 1s 1 lần trong 10s -> chết cơm 
	- cách 1 : khi redis timeout thì redis bắn noti cho service (even driven)
	- cách 2 : gom lại trong sorted set, chỉ cần 1 thread chạy 1 s một lần để lấy tất cả driver có thời gian < now()
#### good solution 

- delay queue, lúc gửi matching request cho driver thì đồng thời ném vào delay queue (AMZ SQS) 10s. Consumer nhận và check nếu người đó đã accept/decline đơn đó chưa, nếu chưa thì gửi tiếp cho driver khác và gửi vào delay queue tiếp.
- cần handle edge case là khi khách hàng bấm accept phút cuối + delay queue đọc ra và check thấy người đó chưa accpet/decline -> gửi cho driver khác -> lúc driver khác đó accept tiếp thì update bằng optimistic lock và throw lỗi là được. 

#### greate solution 

- dùng AMZ step function hoặc [[Temporal]] (uber) để đảm bảo mọi đoạn code đều được lưu trạng thái xuống db -> service chết thì lúc sống lại chạy tiếp từ nơi đã die. 
- Tăng độ phức tạp của hệ thống 




## How can you further scale the system to reduce latency and improve throughput?

- tăng người dùng 100x -> chia service theo tỉnh thành, quốc gia. + consistency hashing để chia dữ liệu trong thành phố đông dân. 



> [!Interview question]
> Ghi chú thông thường
> #todo 