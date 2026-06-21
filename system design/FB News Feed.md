
## Functional requirements

- Users should be able to create posts
- Users should be able to friend/follow people (these are uni-directional follow relationships)
- Users should be able to view a feed of posts from people they follow, in chronological order
- Users should be able to page through their feed

## Non functional requirements 

- Prefer high availability of view and post new feed + eventually consistent for posting 
- Low latency for view feed and create new post (< 500ms)
- Scale up to 2 Bilion users. Read write ratio : 100 : 1
- Unlimited follow/follower

# Core entity

- User
- Follow
- Post

# API 

- Users should be able to create posts
	- POST /api/v1/posts -> postId
	- {
		- content : "", 
		- image : ""
	- }

- Users should be able to friend/follow people (these are uni-directional follow relationships)
	- POST /api/v1/follow?userid=123 -> SUCCESS / FAIL
	- Note bài viết gốc bảo PUT cho idempotent, mà fb chính thống vẫn dùng POST đây, miễn mình xử lý idempotent là được mà :)) 
	- ![[Pasted image 20260619063049.png]]

- Users should be able to view a feed of posts from people they follow, in chronological order
- Users should be able to page through their feed
	- GET /api/v1/feed?pageSize=20&cursor=someTimestamp -> list of Post

# High level design 

## How will users be able to create text posts?

![[Pasted image 20260617211456.png]]

The flow would be : 
1. user create a post and front end call API /api/v1/posts
2. API gateway do cross cutting concern like routing, authen, rate limit
3. Post service create new record in Posts table
4. Return postId and http status code = 200 for user

## How will users be able to friend/follow people?

![[Pasted image 20260617212141.png]]

The flow would be : 
1. user follow a user with userId = 123, so frontend need to call api /api/v1/follow?userid=123
2. API gateway do cross cutting concern like routing, authen, rate limit
3. User service create new record in Follows table as setting up relationship
4. Return http code = 200 for user

With Follows table in cassandra, we choose followee as partition key, follower as sort key. And create Global secondary index (just copy real data) with follower as partition to support both type of query.

## How will users be able to view a feed of posts from people they follow? Don't worry about pagination yet.

![[Pasted image 20260617213107.png]]

The flow would be : 
1. User scoll their feed, front end call API /api/v1/feed?pageSize=20&cursor=someTimestamp to get list of Feed
2. API gateway do cross cutting concern like routing, authen, rate limit
3. Feed service will get all followee of user, and get all posts of all followee and sort by timestamp
4. Return list of feed for that user with pagesize and cursor

We may have Global secondary index (creatorId as PK, created_at as sort key) to query all sorted post of a user very quickly 

Because : 
- user can follow many people
- a people can have a lot of post
- total post is very large
- -> current solution is not scale

## How will users be able to page through their feed? (i.e., infinite scroll - scroll down to see older posts without reloading the entire page)

The flow would be : 
1. like above, we could use paganation base on cursor = someTimestamp. 
2. For example : /api/v1/feed?pageSize=20&cursor=someTimestamp, after scoll first 20 feed, the next query should be /api/v1/feed?pageSize=20&cursor=lastTimestamp where lastTimestamp is the last timeStamp of first 20 feeds.
3. Feed service get all follow of a user, get all posts in GSI. And get only post older than lastTimestamp
4. Feed service return the next cursor along with the posts, so the front end easly to get next cursor

Thử thách : Khi người dùng xem xong bài viết thì đánh dấu để không hiện lại bài viết đó kiểu gì?
- Dùng bảng user_seen_posts để lưu (user_id, post_id, seen_at)  
- Dùng bloom filter thì ok với hàng tỷ dữ liệu, nhưng mà mỗi lần query list of post thì phải check trong redis, và gặp trường hợp query list 100 bài post nhưng đều đã seen :v. Và nếu dùng cassandra để lưu dữ liệu pre_computed_at thì khó có thể mà xóa được, vì sẽ tạo tombstone. Hoặc có thể dùng cũng được, nhưng sẽ chạy clean tombstone và cuối ngày. 
- Dùng redis lưu sorted set kiểu key là userId, member là postId, score là created_at -> khi người dùng đọc xong thì xóa luôn trong redis :v  

# Deep dive

## How do we efficiently read feeds for users who follow thousands of accounts while maintaining low latency?

![[Pasted image 20260618073323.png]]

- Each time we want to get feeds for a user, we get all people this user follow in Follows table, and get all post of all followees and order by timestamp. -> consume a lot of sort, query, ... -> low latency #fanout_on_read (a single query fanout to create many other query)
- So we should pre-compute + pre-sort all feed by created_at for a user when followee have new post (~ nhà hàng xử lý đồ ăn trước khi bạn đến) -> mỗi user chỉ cần vào Pre_computed_feed để đọc bài viết của mình 
- When a user create a post we create new record on Posts table, and publish (post_id, created_by) in to kafka. 
- Fanout service (consumer) query all follower, then create new record in pre_computed_feed. #fanout_on_write

- Làm sao đánh dấu user đọc một post rồi nhỉ? 
	- Nếu xóa khi đọc xong trong pre_computed_feed thì không ổn lắm, vì đây là cassandra nên khi xóa nó tạo lệnh xóa thôi chứ ko xóa ngay -> và khi đọc mới tổng hợp dữ liệu -> tăng latency
	- Dùng bloom filter + giới hạn chỉ 200 bài viết trong cassandra + dùng TTL của cassandra  để tự xóa 

- Giới hạn product + tradeoff : 
	- Nếu một người dùng follow 100k người khác -> bảng pre_computed_feed của họ có 100k posts -> giới hạn số lượng một người có thể follow lại. Hoặc nếu follow 100k user thì có thể chấp nhận chậm 10p lag chẳng hạn. Giống fb chỉ cho tối đa 5k bạn bè. 
	- Giới hạn bảng pre_computed_feed của từng user chỉ chứa 200 bài viết mới nhất. 10byte each postId -> 2KB each 200 postId -> 1B user, we need 2kGB = 2TB (tầm 5tr VND - quá rẻ)

- Vấn đề của #fanout_on_write  : một người nổi tiếng có 2 triệu người theo dõi -> khi đăng post phải ghi cho 2tr bản ghi -> toang 

- Nếu người dùng lướt > 2000 bài thay vì 200 bài như tính toán dự kiến của mình thì sao 
	- monitor, nếu số lượng user lướt 2000 bài chỉ 1% thì chấp nhận việc lướt nhiều hơn sẽ bị chậm khi dùng #fanout_on_read  
	- số lượng bài của từng user của thể tunning chứ không cố định chỉ là 200

## How would you design the fanout worker and Cassandra writes so that precomputing feed entries stays reliable and efficient when a single post needs to be inserted into a very large number of follower feeds?

- Because we use kafka, we only commit offset of message when we fanoutted all post to pre_computed_feed + we insert into pre_computed_table by batch. And we partition in a topic by postId. So it is reliable and efficient. 
- Bao nhiêu partition là đủ ở đây? cần chia thêm topic không? Hàng trăm triệu post một ngày thì kafka thành bottleneck không? Nếu chỉ một topic thì mỗi partition chứa bao nhiêu dữ liệu cần để xử lý #todo 

## How do we avoid write amplification when a celebrity user with millions of followers creates a post that needs to update millions of other users feeds?

- Cách 1 : Vẫn dùng kafka để xử lý dần dần, khi người nổi tiếng tạo post thì dual-write vào db và vào kafka. 
	- Có một đội consumer để xử lý update vào bảng pre_computed_feed. 
	- Có thể chỉ dùng kafka để xử lý người có hàng triệu follower.
	- Chia long running task topic và short running task topic để cái xử lý lâu không block cái xử lý nhanh 

- Cách 2 : Thay vì #fanout_on_write ở bảng pre_computed_feed của 1 user có hàng triệu người theo dõi
	- bỏ qua #fanout_on_write  
	- Lúc read thì kiểm tra trong list của người đó có theo dõi ai nổi tiếng ko? nếu có thì lấy postId mới ra và gộp với pre_computed_feed để hiển thị 

## How can we handle "hot posts" (viral content from any user) which are read millions of times per minute? Focus on preventing database hot spots.

![[Pasted image 20260618075643.png]]

- Cache nội dung + metadata của một bài post trên redis, vì bài post ít khi được sửa đổi nên có thể để TTL dài + dùng cơ chế Eviction là ít sử dụng gần đây nhất (Least recently used)
- Vấn đề : hot key của redis (bài post viral)
	- replica (sao chép) viral post đó ứng với mỗi instance redis hứng riêng sau mỗi pod service, ko phải redis chung 
	- so sánh với cách chia nhỏ hơn nữa một key (Salted Key) thì do ko biết bài viết nào được viral, bài viết nào không nên sẽ không tính toán trước được số Salted Key cần dùng, nên cách replica sẽ ổn hơn. 

