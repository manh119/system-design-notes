
# Functional requirements

1. Users should be able to create and like posts
2. Users should be able to search posts by keyword
3. Users should be able to get search results sorted by recency or like count

constraint : Không được dùng elastic search hoặc full-text index có sẵn 

# Non-functional requirements

1. scale up to 1B daily active user + 1kB posts 
2. High availability for create + like posts, search post + prefer eventually consistency
3. Low latency for create + like posts (< 200ms), search (<500ms), and less than 1 minute for new post is seen by other people


## Estimation 

- 1B user, create a post a day, like 10 post a day, search 1 post a day
- create post : 1B x 1 post a day / (100k seconds a day) = 10k post a second
- Like post : 1B x 10 post a day / (100k seconds a day) = 100k like post a second
- Search post : 10k search a second
- Storage in 10 year, each post is about 1KB : 1Kbyte x 1B post a day x 300 day a year x 10 year = 3 Petabyte -> need some constrain on searching post here

# Entity

- Post
- User
- Like

# API 

1. Users should be able to create and like posts
	- POST api/v1/posts  -> postId
	- {
		- contentPost : {}
	- }
	- POST /api/v1/posts/{postId}/like -> SUCCESS / FAIL

2. Users should be able to search posts by keyword
3. Users should be able to get search results sorted by recency or like count
	- GET api/v1/searchPost?keyword=abc&sortBy=likeCount&page=0&pageSize=10 -> list of Posts

# High level design 

## How will users create posts and like them?

![[Pasted image 20260619215600.png]]

Flow : 
1. user type content to create a post, front end make a request to api api/v1/posts  
2. API gate way do cross cutting concern like routing, rate limiting, authen
3. Post service create new record on Posts table
4. user can like their post, and Post service create new record on Likes table


## How will users be able to search by keyword? (Start simple, we'll optimize later)

![[Pasted image 20260619220304.png]]

Flow would be : 
1. user want to search by keyword, make a request to api/v1/searchPost?keyword=abc&sortBy=likeCount&page=0&pageSize=10 
2. Search service query with like command in cassandra to content column.
3. Search service return list of posts to user

## How will users be able sort their results by recency and like count?

- just sort by created_at or like_count column

# Deep dive

## How can we scale the keyword search to support trillions of posts?

![[Pasted image 20260619223303.png]]

FLow would be : 
1. When user create new post, save in Posts table and publish (postId, content) into kafka
2. Index service get (postId, content) and create inverted index in Inverted_indexs table, 
3. When a user want to search for a keyword, it will search by hash(keyword) to search in Inverted_indexs table (consume O(1)) -> get all post have this keyword
#todo You did not explain the query side beyond finding the keyword bucket and getting post IDs back. To reach the strongest version of this answer, you also need to say how the search service turns those IDs into actual results for users, such as fetching post records and handling ordering on the read path.
#todo You mentioned indexing new posts, but you did not cover how likes are reflected in the search path. Since your system already supports sorting by like count, a stronger answer would connect likes to index updates or to a retrieval structure that can return top posts efficiently.

- Thử thách : 
	- Khi 1 post được tạo, phải update tất cả token có trong post tương ứng (10k update)
	- Một token có thể chứa trong rất nhiều post (100K postId)



## How would you update your reverse index design so that a search for a keyword can quickly return the most recent or most liked posts without scanning a huge posting list?

![[Pasted image 20260620094044.png]]

- Giải pháp ngây thơ : sort tại thời điểm query, từ token -> lấy tất cả postId -> với từng postId query bảng Posts để lấy created_at hoặc like_count -> nghẽn query db + sort hàng triệu postId là ko tốt 
- Cách tốt hơn là tạo nhiều index riêng biệt (đánh đổi storage để lấy tốc độ, lưu các danh sách index đã sắp xếp sẵn) - Cách dùng redis 
	- creation index : với mỗi keyword (token) dùng redis list, khi post mới add vào thì chỉ cần add vào cuối list là được 
	- likes index : với mỗi keyword, dùng sorted set, với member là post_id, score là total like của post_id đó. 
	- Thử thách : gấp đôi dung lượng để chứa post Id và token (keyword). Và với mỗi like -> cần update tất cả các token (word) của bài post đó -> rất nặng 
- [[Search text]] 
- table Inverted_indexs (keyword, post_id []) is not efficient. Because when update a keyword with postId, we need to load list post_id in to the RAM to add new element. 
- We can change to (keyword, post_id) with post_id is snowflake type. So with keyword is partition key and post_id is sort key. We can efficient search keyword to return list of post recency
- We can use sorted set in redis with index:token (member is post_id, score is total like) with limit each token is 1000 member. So we can efficent get the most liked posts. We don't use canssandra here because of like count can change quickly, so it can create tombstone in cassandra.

## How will the system handle queries with multiple keywords (e.g. "taylor AND swift")?

Cách 1 : 

- we can search for each token, and intersect k sorted posts to get the result
- và chỉ lọc lấy những cụm có "taylor swift" sát nhau, chứ ko phải "taylor ...1000 từ khác ... swift"
- tìm kiếm bài post có thể nhanh (vì index qua redis), nhưng với keyword có hàng triệu post, gộp lại và intersect + filter sẽ khá tốn time, tăng latency 
- Intersect có thể dùng 2 pointer trên 2 mảng đã sắp xếp với O(m + n)

Cách 2 : 

- Cách tốt hơn là đánh index cả cụm 2 từ liên tiếp - bigrams, ví dụ cụm "a b c" thì ngoài đánh index a, b, c thì đánh index cả a b, b c nữa. -> tốn dung lượng gấp đôi, gấp ba ở redis. 
- Có thể chỉ đánh cụm bigrams (2 từ liên tiếp) xuất hiện nhiều, bằng cách dùng count min sketch. Và quay về dùng cách 1 nếu cụm đó ko xuất hiện nhiều (fallback)

## How would you implement the intersection efficiently when one keyword is very rare and another is extremely common?

- we can get a list post of rare keyword, and then search post_id in extremely common posts's keyword. Because posts_id is sorted, so the search is very efficient

## How can we ensure searches queries are still fast in the case of many results (like "taylor swift")?

- we make a tradeoff hear, only save top 1000 postid with a token, so with case many results we still have efficient query -> can silently drop valid match
- We can keep the top 100 popular search in cache like redis, so we can handle efficent this case

## How can we make ingestion scalable and fast with millions of posts and billions of likes?

- Mỗi lần post một bài viết hay like một bài viết 100 từ (token) -> phải update index tương ứng của 100 từ đó (token)

Posts : 

- with billion of posts, tăng instance + dùng kafka để buffer + partition kafka để xử lý song song + redis sharding theo keyword. (tìm token trên redis vẫn nhanh vì dùng consistet hashing)

Likes : 

- with billion of likes, cũng dùng kafka + sharding như xử lý posts. Nhưng với like thì nó biến đổi liên tục, đặc biệt với các bài post viral thì like tăng liên tục. 

- Cách 1 : thêm batcher service gom lại, 30s mới update để đẩy vào kafka một lần để update redis -> hiệu quả với viral post, nhưng ko hiệu quả với normal post + mất công thêm batcher 

- Cách 2 : Lúc ghi thì ghi xấp xỉ, nhưng lúc đọc thì lấy cái mới nhất.
	- Có Like batcher service để gom like lại, chỉ update Redis index khi số like có ngưỡng là 2, 4, 8, 16, ... (exponential) -> 1000 like chỉ cần update 10 lần thay vì 1000 lần vào redis 
	- Order của bài viết dựa vào like index vẫn xấp xỉ đúng 
	- Lúc query top N post, thì lấy 2 x N posts, và lấy giá trị like mới nhất từ Like batcher để vẫn hiển thị đúng 
	- Cái này vận dụng tính chất một bài viết có thể được update nhiều bởi like, nhưng search thì ít hơn (write heavy >> read heavy). Kiến trúc kinh điển two-architecture 

## How can we optimize the storage requirements of the system?

- Giới hạn index lại, chỉ 10k postId thôi, vì hiếm ai lướt đến mục 100 
- Đánh dấu từ khóa ít dùng, ko được ai tìm kiếm trong năm và bỏ ra khỏi redis, đưa vào blob storage giá rẻ -> khi người dùng tìm kiếm từ khóa Lạnh (cold) ít dùng thì chấp nhận tốn latency nhưng đỡ tốn nhiều tiền 
- **Khi Đọc (Read Path):** User ➔ CDN (Edge Cache) ➔ API Gateway ➔ Search Service ➔ Check Redis (Hot) ➔ Nếu thiếu thì check S3 (Cold) ➔ Trả về top $2N$ kết quả xấp xỉ ➔ Gọi Like Service để Sắp xếp lại real-time (Re-ranking) ➔ Trả về $N$ kết quả chuẩn xác cho User.
- **Khi Ghi (Write Path):** Bài viết/Lượt thích ➔ Kafka (Buffer/Partition) ➔ Ingestion Service ➔ Ghi gom cụm (Batch) hoặc ghi theo cột mốc (Milestone) vào các Shard Redis phân tán theo Keyword. Định kỳ dọn dẹp các chỉ mục quá dài hoặc quá ít người dùng sang S3.
