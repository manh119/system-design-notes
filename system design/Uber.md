
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

- Rider and Driver report location of each other
- POST /api/v1/drivers/location
- POST /api/v1/riders/location

# High-Level Design

## How would you give users a estimated fare based on their start location and destination?

![[Pasted image 20260614075243.png]]


Rider flow would be:
1. Rider enter destination and app get the current location and click get estimated fare in the app
2. Trip service estimate price based on location, peak hour, ... and create a new record in Estimated_trip table
3. Return estimated_id, estimated_price, estimated_time to the rider

## How will riders be able to request a ride based on the estimated fare?

![[Pasted image 20260614081000.png]]

Rider flow would be:
1. Rider click the estimated trip and book for a trip
2. Booking service create new record on bookings table with status = PENDING
3. Booking service send notification to the rider and wait for an accept
4. Rider accept the trip, and booking serice change of record in the bookings table = ON_GOING
5. Booking service return the booking_id to the rider

## How does your system match riders to the best driver for their ride?

![[Pasted image 20260614082229.png]]

The flow would be : 
1. Drivers report their current location each 10s, and booking service save current driver's location to the Drivers table
2. When booking service looking for an driver, it query all AVAILABLE driver, and caculate a score base on current driver location, rating, ... and get the driver having highest score for that trip

## How does your system notify matched drivers and allow them to accept/decline rides?

- The system can notify matched drivers through FCM (Firebase cloud message) or something like that in IOS. And wait 1 minute timeout for this driver to accept/decline before we move the another driver.
- Driver can accept/decline for a trip through http API

# Deep Dives

## How can you handle the high write throughput from drivers sending location updates every couple seconds and efficiently perform proximity searches for matching?

![[Pasted image 20260614091302.png]]

- With 20M daily active driver, and drivers report their current location each 10s. We have 20M x 10 ^ 5 / 10s request per day = 2M report location per second 
- Assume we partition our service by city, so the peak would be 1M report location per second
- No database can handle this write request. -> we can use redis to save this information - GEOHASH
- When find nearest location, we can use built-in function of GEOHASH in redis for efficiently

## How would you partition and query Redis geospatial data near city boundaries so that a rider close to the edge still matches with nearby drivers in adjacent partitions?

- we can query both adjacent city when location of drivers is nearby the boundary of the city

## How do we guarantee each driver receives at most one ride request at a time?

- When we send driver a ride request, we can keep distributed lock an distributed lock in redis with TTL = 1 minute (time for driver to accept / decline a trip). And release lock when timeout or driver accept/decline a trip.
- The next time we want to send ride request to a driver, we will check if they have lock in redis, so if have, we will go to another drivers.  

## How would you implement the Redis lock safely so that only the service instance that acquired the lock can release it when the driver responds?

- Why do we need only the service instance that acquired the lock can release it? It is distributed lock, so any instance can lock or release it.
- It is edge case : when service A aquire a lock A, and TTL is 1 minute. 
- Imagine this scenario: Instance A acquires a lock for Driver X with a 1-minute TTL. Due to a slow network or GC pause, Instance A takes longer than 1 minute to process. The TTL expires, the lock is automatically released, and Instance B acquires a new lock for Driver X. 
- Now Instance A finishes its work and calls DEL on the Redis key — it just deleted Instance B's lock, not its own. Driver X is now unprotected and could receive a second ride request. The fix is straightforward: when acquiring the lock, store a unique token (e.g., a UUID) as the lock's value. 
- When releasing, use a Lua script to atomically check that the stored value matches your token before deleting — this guarantees only the original acquirer can release it. This is actually the standard Redis Redlock pattern recommendation. -> là sao ???? 


## How can we ensure no ride requests are dropped during peak demand periods?

![[Pasted image 20260614094920.png]]

- Should we add kafka before Booking service, or after Booking service ? 
	- If before, but it is HTTP call, so how we can handle that ?
	- And Booking service is auto scale based on RAM, CPU usage -> it is better to place kafka after that ?

- When user booking, we have HTTP API call to produce serivce, this service only forward this request to kafka and gerenate a job id, and return this job id for the users. And the user can call API check-status for this job id each 5s. (Hình như lý do không giữ luồng để đợi là bởi vì có thể timeout, và ví dụ 200 request giữ luồng thì sẽ tốn tomcat thread nên ko phục vụ được request khác)
- Thế thì api check-status call đến produce service hay Booking service ??? 
- So Kakfa can ingest any peak traffic to ensure no ride request are dropped, and we can scale booking service base on length of kafka queue. 
- And after processed request, Booking service response to the produce


## How would you implement consumer acknowledgment and retries in Kafka so a ride request is not lost if Booking Service crashes halfway through matching?


> [!Interview question]
> Ghi chú thông thường