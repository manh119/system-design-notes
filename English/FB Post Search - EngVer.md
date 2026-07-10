# Funtional requirement 

> first, clarify + align before jumping 
> similar to fb
> alows : create, like, search, sorted
> out of scope : fuzzy search, personalized ranking, image search, real-time update 
> not use elastic search 
> not recommend, but scale
> next step 

First, I'd like to clarify the requirements and align on the scope before jumping into the design.

> We need to align on the priorities. (thống nhất về điều gì)

We're building a search system for a social network similar to Facebook.

>**that is similar to Facebook** l

The platform allows users to :

- Users can create posts.
- Users can like posts.
- Users can search posts using one or more keywords.
- Search results can be sorted by recency or by like count.

I'd also like to clarify what's intentionally out of scope. I'll assume we don't need:

- fuzzy search, such as matching similar words,
- personalized ranking,
- image or video search,
- or real-time updates while users are viewing the search page.

Another important assumption is that we're not allowed to use Elasticsearch or any existing full-text search engine. 

So the main challenge here isn't building a recommendation system, but designing a scalable infrastructure that can efficiently index billions of posts.

If these assumptions sound reasonable, I'll move on to the next step 

---
# Non functional requirement 

> fast, return in < 500ms.
> high volume of requests 
> searchable in a short amount of time, < 1 minute, not strictly 
> All posts must be discoverable, relax expect 
> highly available, downtime 

1. The system must be fast, median queries should return in < 500ms.

2. The system must support a high volume of requests (we'll estimate this later).

3. New posts must be searchable in a short amount of time, < 1 minute. we’re not building a strictly real-time system, but we still need near-real-time indexing.

4. All posts must be discoverable, including old or low-engagement posts. However, I think it’s reasonable to relax latency expectations for these cold or long-tail posts.

5. The system should be highly available. since search is a core feature and downtime would significantly impact user experience.

> relax the deadline
> relax the rules

---

# Scale Estimation

> 1B uses, create 1 post, like 10 post, search 1 
> write=heavy vs read heavy 

We'll assume Facebook has 1B users. On average each user produces 1 post per day (maybe 20% do 5 posts and 80% do 0 posts) and Likes 10 posts. For ease of calculation, we'll assume 100k seconds in a day

- First let's look at the volume of writes:

Posts created: 1B * 1 post/day / (100k seconds/day) = 10k posts/second
Likes created: 1B * 10 likes/day / (100k seconds/day) = 100k likes/second

- Now let's look at reads.

Searches: 1B * 1 search/day / (100k seconds/day) = 10k searches/second

While this is a lot (and may burst the 10x this value or more), our system is write-heavy vs read-heavy

- Storage : 

Finally let's evaluate the storage requirements of the system. First let's assume Facebook is 10 years old and that the full post metadata is 1kb (probably an overestimate).

Posts searchable: 1B posts/day * 365 days/year * 10 years = 3.6T posts
Raw size: 3.6T posts * 1kb/post = 3.6 PB

Wow, that's a lot of storage! We're going to need to find some way to constrain this in our search system.

---

# Defining Core Entities 

> user
> post 
> like, mainly care aggregate like count 

> We'll start by identifying the core entities we'll be working with. Fortunately, for this problem, the core entities are very simple:

1. **User**: This entity creates and likes posts.
2. **Post**: This is the thing that we're searching! It has a content, is created by a user, and implicitly has a like count.
3. **Like**: Likes are created when a user likes a post, but for this problem we mostly care about the _count_ of likes.

> I'd rather stay at home than go with you (tôi thà làm gì đó hơn làm gì đó)

---

# API / System Interface 

Our APIs are straightforward. We have two paths: a query path for searching and a write path for creating posts and likes.

- `searchPosts(query, sortBy)` where `sortBy` can be either recency or like count.
- One for creating posts, for example: `createPost(userId, content)`
- Another for liking a post, for example: `likePost(userId, postId)`

![[Pasted image 20260705172434.png]]

---

# High-Level Design

> **Next, I’d like to start with a high-level design. The goal here is to build a simple system that satisfies our functional requirements before we go deep into optimizations in our deep dive.

---

## 1) Users should be able to create and like posts.

> allow users create and like 
> accept and write 
> are consumed by 
> over-simplified


![[Pasted image 20260705171824.png]]

These events (create Posts and create Likes) are consumed by an **Ingestion Service**, which is responsible for updating our search index.

 > This solution is obviously over-simplified and I'll come back to solve its problems

---

## 2) Users should be able to search posts by keyword.

>go through an API gate way handle 
>is forwared to 
>find all post that contain

When a user searches for posts, the request first goes through an **API Gateway**, which handles authentication, rate limiting, and routing.

Then the request is forwarded to a **Search Service**, which is horizontally scalable. 

In order to allow users to search posts by keyword, we need to be able to find all of the posts which _contain_ that keyword. With trillions of posts and petabytes of data, this is not a small feat!

---

### Naive Approach 

> naive solution, technically return, terribly slow
> is not viable

The naive solution to this problem is to keep all of the posts in a relational database and use a query like

```
SELECT * FROM posts WHERE content LIKE '%keyword%';
```

This would technically return the correct results for a given query!

Unfortunately, it's terribly slow because our database needs to look at every post and try to see if its contents contain the keyword _at query time_.

> So this approach is not viable for our latency requirement.

---

### Greate solution : Inverted Index

![[Pasted image 20260706070329.png]]

> map keyswords to posts 
> use redis 
> flow : break them apart, append to list 
> challenges : redis, hot key, fanout on write 

The idea behind an inverted index is that we can create a dictionary which maps keywords to the documents that contain them. 

Instead of:

- post → words

We store:

- word → list of post IDs

To keep things simple and fast, let's use Redis for this. Redis will keep these inverted indexes in memory which makes their queries blazing fast. 

FLow : When posts are created, we'll break them apart inside the Ingestion Service into each keyword that could possibly match (a process known as "tokenization") and then append that post's ID to each keyword contained.

##### Challenges

There are obviously durability concerns with redis, but they are surmountable and we can handle them with a durable alternative like MemoryDB or in a deep-dive.

hot keys problem : Note that these post ID lists are going to get very large, especially for common keywords. We'll have to address this later.

fan-out on write : We also need to write to many keys for every post since a given post might have 10-1,000 keywords. We'll need to handle some of the scaling challenges associated with this.

---
## 3) Users should be able to get search results sorted by recency or like count.

>sort by receny or like count 
>request time sort : flow : list post id in redis -> query db to get created time -> sort 
>common keywords -> query 10s Milion time 

Next we'll move to our last requirement which is to be able to sort the results by either recency or like count - that is, if we search for "Taylor" and sort by recency, we want to be able to show the most recent posts that were created. If we sort by likes, we want to see the top liked posts.

### Naive Approach: Request-Time Sorting

The most naive solution we can employ is to grab all of the post IDs for a given keyword, look up the timestamp or like count for each post id in the database, then sort those posts in memory.

This is ... not great.

##### Challenges

The biggest problem with this approach is that the number of Post IDs might be very large for common keywords, may be 10s milions post ids. For each of these post id we need to make a lookup - 10s of millions of them. Finally, sorting millions of items at request time adds unnecessary latency to our system.

---

### Better Approach: Precomputed Sorted Indexes

>two separate indexes, sorted by time and like 
>redis list 
>redis sorted set 
>post is created, add to both, like happend 
>challenges : double storages, like frequency 

A different approach would be to have two separate indexes: one sorted by the creation time and one sorted by the like count. 

For the creation index keys, we can use a standard Redis list. We're always going to append to this list and our queries will only take from the last elements.

For the likes index, each key can use a [Redis sorted set](https://www.hellointerview.com/learn/system-design/deep-dives/redis#redis-for-leaderboards). The sorted set allows us to keep a list of items ordered by a score in the same way that a priority queue or sorted list might work, with the same time complexity of insertions and queries.

When a new post is created, we'll add it to both indexes for every keyword it contains. When a like event happens, we'll update the score in our sorted set for the likes index

![[Pasted image 20260706071648.png]]

##### Challenges

We've doubled the amount of storage required for our indexes here. This is a valid tradeoff for the massive improvement in query performance, but it does cost more.

We also introduced a new problem: likes are happening quite frequently. Each like event requires us to update many scores so that the like indexes are up-to-date. This puts a lot of stress on our system, which we'll plan to address later.

> so that = để làm gì (I saved money **so that** I could buy a new laptop.)

# Deep dive 