# Funtional requirement 

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

Excluding personalization is especially helpful because every user receives the same results for the same query, which reduces overall system complexity.

Another important assumption is that we're not allowed to use Elasticsearch or any existing full-text search engine. 

So the main challenge here isn't building a recommendation system, but designing a scalable infrastructure that can efficiently index billions of posts.

If these assumptions sound reasonable, I'll move on to the next step 

---
# Non functional requirement 

> Low latency search (< 500ms)
> high volume of traffic
> freshness less then one minute 
> all posts are discoverable
> highly available

The system needs to prioritize **low latency search**, meaning that for most queries, we should aim for a **response time under 500 milliseconds**.

At the same time, the system should handle a **high volume of traffic**, so we’ll need to think about horizontal scalability rather than optimizing for a single node.

Another important requirement is **freshness** — new posts should become searchable quickly, ideally within **less than one minute**. So we’re not building a strictly real-time system, but we still need near-real-time indexing.

We also need to ensure that **all posts are discoverable**, including old or low-engagement posts. However, I think it’s reasonable to relax latency expectations for these cold or long-tail posts.

> relax the deadline
> relax the rules

Finally, the system should be **highly available**, since search is a core feature and downtime would significantly impact user experience.

---

# Scale Estimation

> wirtes : 1 post per day -> 1 billion post per day -> 10k post per second
> likes : 10 likes per day -> 10 bilion likes per day -> 100k likes per second 
> read : 1 search per day -> 10k search per second 
> fairly balanced but write-intensive

> **Now I’d like to estimate the scale so I can make better architectural decisions instead of relying on assumptions.**

Let’s assume we have around **1 billion users**.

For writes:

- Each user creates about **1 post per day**, so that’s roughly **1 billion posts per day**.
- Spread over a day, that becomes around **10,000 posts per second**.

For likes:

- Each user performs around **10 likes per day**, so that’s about **10 billion likes per day**.
- That translates to roughly **100,000 likes per second**.

For reads:

- If each user performs about **1 search per day**, that gives us around **10,000 searches per second**.

So overall, we have a system that is fairly balanced, but still **write-intensive**, especially when considering both posts and likes.

---

# Storage Estimation

> 10 years, each post is 1KB -> 3.6 trillion posts total, 3.6 petabytes of data 
> distibuted storage + hot + cold archival data 

> **Next, I’ll estimate storage to understand whether we can keep everything in memory or need tiered storage.**

Let’s assume the system has been running for **10 years**, and each post, including metadata, is about **1 KB**.

That gives us:

- 1 billion posts per day
- × 365 days × 10 years
- ≈ 3.6 trillion posts total

In terms of storage:

- 3.6 trillion posts × 1 KB
- ≈ **3.6 petabytes of data**

So clearly, we cannot keep everything in memory or even on a single storage system. We’ll need:

- distributed storage
- and likely a separation between **hot data** and **cold archival data**

---

# Defining Core Entities 

> user
> post 
> like, mainly care aggregate like count 

> **Let me first define the core entities in this system. Fortunately, this problem has a relatively simple domain model.**

We essentially have three main entities:

First, we have the **User**, which represents the actor in the system. A user can create posts and interact with content.

Second is the **Post**, which is the main object we are indexing and searching. A post will contain the text content, it is created by a user, and it also has metadata like creation time and a like count.

Third is the **Like** entity. Users can like posts, and for this problem, we mainly care about the **aggregate like count per post**, rather than modeling each like individually in the search system.

> I'd rather stay at home than go with you (tôi thà làm gì đó hơn làm gì đó)

Since the data model is quite simple, I’ll move on quickly to the system interface.

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

Our first requirement is on the write path, allowing users to create and like posts. We need to be able to accept these calls and write them to our database.

![[Pasted image 20260705171824.png]]

These events are consumed by an **Ingestion Service**, which is responsible for updating our search index.

 > This solution is obviously over-simplified and I'll come back to solve its problems

---

## 2) Users should be able to search posts by keyword.

>go through an API gate way 
>forwared to search service 
>find all post that contain, 

> Next, we need to allow users to actually search.

When a user searches for posts, the request first goes through an **API Gateway**, which handles authentication, rate limiting, and routing.

Then the request is forwarded to a **Search Service**, which is horizontally scalable. 

In order to allow users to search posts by keyword, we need to be able to find all of the posts which _contain_ that keyword. With trillions of posts and petabytes of data, this is not a small feat!

How do we actually find posts by keyword efficiently at scale?

---

### Naive Approach 

> like query
> look at every post 

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

The idea behind an inverted index is that we can create a dictionary which maps keywords to the documents that contain them. In this case, we'll create a map from keywords to posts!

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

The most naive solution we can employ is to grab all of the post IDs for a given keyword, look up the timestamp or like count, then sort those in memory.

Flow : Assuming we're sorting by recency, let's walk through an example. We're going to first make a request to our index for a given keyword. It will return to us a list of Post IDs. For each of these post IDs we'll query the Post Service for the created timestamp. Once we retrieve these timestamps, we can sort the posts in the Search Service and return to the user.

This is ... not great.

##### Challenges

The biggest problem with this approach is that the number of Post IDs might be very large for common keywords. If a keyword like "Taylor" has 10s of millions of results, we could easily have payloads returned from our index which are 100s of megabytes. 

In addition, for each of these results we need to make a lookup - 10s of millions of them. Finally, sorting millions of items at request time adds unnecessary latency to our system.

---

### Better Approach: Precomputed Sorted Indexes

>two separate indexes, sorted by time and like 
>redis list 
>redis sorted set 
>post is created, add to both, like happend 
>challenges : double storages, like frequency 

A different approach would be to have two separate indexes: one sorted by the creation time and one sorted by the like count (I'll refer to these as the creation index and likes index going forward). Using our Redis-based approach from earlier, we can have separate keys depending on whether we're sorting by Likes or Creation date.

For the creation index keys, we can use a standard Redis list. We're always going to appending to this list and our queries will only be taking from the last elements.

For the likes index, each key can use a [Redis sorted set](https://www.hellointerview.com/learn/system-design/deep-dives/redis#redis-for-leaderboards). The sorted set allows us to keep a list of items ordered by a score in the same way that a priority queue or sorted list might work, with the same time complexity of insertions and queries.

When a new post is created, we'll add it to both indexes for every keyword it contains. When a like event happens, we'll update the score in our sorted set for the likes index

![[Pasted image 20260706071648.png]]

##### Challenges

We've doubled the amount of storage required for our indexes here. This is a valid tradeoff for the massive improvement in query performance, but it does cost more.

We also introduced a new problem: likes are happening quite frequently. Each like event requires us to update many scores so that the like indexes are up-to-date. This puts a lot of stress on our system, which we'll plan to address later.