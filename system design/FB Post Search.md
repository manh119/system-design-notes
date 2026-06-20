
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

- we can search for each token, and intersect k sorted posts to get the result

## How would you implement the intersection efficiently when one keyword is very rare and another is extremely common?

- we can get a list post of rare keyword, and then search post_id in extremely common posts's keyword. Because posts_id is sorted, so the search is very efficient

## How can we ensure searches queries are still fast in the case of many results (like "taylor swift")?

- we make a tradeoff hear, only save top 1000 postid with a token, so with case many results we still have efficient query -> can silently drop valid match
- We can keep the top 100 popular search in cache like redis, so we can handle efficent this case

## How can we make ingestion scalable and fast with millions of posts and billions of likes?

- with billion of likes, redis cluster can handle effiecent
- with billion of posts, cansandra can handle it scalable with partition key is token, and post_id

## How can we optimize the storage requirements of the system?

- with older post, we can move to cold storage to optimize the storage
