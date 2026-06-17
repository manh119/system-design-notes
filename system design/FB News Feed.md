
## Functional requirements

- Users should be able to create posts
- Users should be able to friend/follow people (these are uni-directional follow relationships)
- Users should be able to view a feed of posts from people they follow, in chronological order
- Users should be able to page through their feed

## Non functional requirements 

- Prefer high availability + eventually consistent
- Low latency for view feed and create new post (500ms)
- Scale up to 2 Bilion users 

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
3. User service create new record in Follows table
4. Return http code = 200 for user

## How will users be able to view a feed of posts from people they follow? Don't worry about pagination yet.

![[Pasted image 20260617213107.png]]

The flow would be : 
1. User scoll their feed, front end call API /api/v1/feed?pageSize=20&cursor=someTimestamp to get list of Feed
2. API gateway do cross cutting concern like routing, authen, rate limit
3. Feed service will get all followee of user, and get all posts of all followee
4. Return list of feed for that user with pagesize and cursor

## How will users be able to page through their feed? (i.e., infinite scroll - scroll down to see older posts without reloading the entire page)

The flow would be : 
1. like above, we could use paganation base on cursor = someTimestamp. 
2. For example : /api/v1/feed?pageSize=20&cursor=someTimestamp, after scoll first 20 feed, the next query should be /api/v1/feed?pageSize=20&cursor=lastTimestamp where lastTimestamp is the last timeStamp of first 20 feeds.
3. the feed service should return the next cursor along with the posts, so the front end easly to get next cursor

# Deep dive

## How do we efficiently read feeds for users who follow thousands of accounts while maintaining low latency?

- Each time we want to get feeds for a user, we get all people this user follow in Follows table, and get all post of all followees and order by timestamp. -> consume a lot of sort, query, ... -> low latency #fanout_on_read
- So we should pre-compute + pre-sort all feed for a user when followee have new post by having Pre_computed_feed to save all sorted feed 