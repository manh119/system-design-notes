
# Functional requirements

1. Users should be able to create and like posts
2. Users should be able to search posts by keyword
3. Users should be able to get search results sorted by recency or like count

constraint : Không được dùng elastic search hoặc full-text index có sẵn 

# Non-functional requirements

1. scale up to 1B daily active user + 1kB posts 
2. High availability for create + like posts, search post + prefer eventually consistency
3. Low latency for create + like posts (< 200ms), search (<500ms), and less than 1 minute for new post is seen by other people

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