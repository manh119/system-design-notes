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

## Scale Estimation

> **Now I’d like to estimate the scale so I can make better architectural decisions instead of making assumptions.**

Let’s assume we have around **1 billion users**.

For writes:

- Each user creates about **1 post per day**, so that’s roughly **1 billion posts per day**.
- Spread over a day, that becomes around **10,000 posts per second**.

For likes:

- Each user performs around **10 likes per day**, so that’s about **10 billion likes per day**.
- That translates to roughly **100,000 likes per second**.

So one important observation here is that **likes are an order of magnitude higher than post creation**, which suggests our system might be more write-heavy than it first appears, especially if likes affect ranking.

For reads:

- If each user performs about **1 search per day**, that gives us around **10,000 searches per second**.

So overall, we have a system that is fairly balanced, but still **write-intensive**, especially when considering both posts and likes.

---

## Storage Estimation

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
- and likely a separation between **hot index data** and **cold archival data**

> So overall, this tells me we need a system that is optimized for fast keyword lookup on a massive dataset, with strong support for incremental indexing and a clear separation between hot and cold data.



---

## Defining Core Entities (Văn nói)

> **Let me first define the core entities in this system. Fortunately, this problem has a relatively simple domain model.**

We essentially have three main entities:

First, we have the **User**, which represents the actor in the system. A user can create posts and interact with content.

Second is the **Post**, which is the main object we are indexing and searching. A post will contain the text content, it is created by a user, and it also has metadata like creation time and a like count.

Third is the **Like** entity. Users can like posts, and for this problem, we mainly care about the **aggregate like count per post**, rather than modeling each like individually in the search system.

> I'd rather stay at home than go with you 

So in summary, the key searchable object is really the **Post**, enriched with metadata like timestamp and likes.

Since the data model is quite simple, I’ll move on quickly to the system interface.

---

## API / System Interface (Văn nói)

I see the system as having two main flows:

A **write path**, where we ingest data into the system, and a **read path**, where users perform searches.

On the write side, we have two main APIs:

- One for creating posts, for example: `createPost(userId, content)`
- Another for liking a post, for example: `likePost(userId, postId)`

On the read side, we have a search API like:

- `searchPosts(query, sortBy)`

where `sortBy` can be either recency or like count.

In a real production system, these actions might not directly hit a database. Instead, they would likely go through an **event streaming system like Kafka**, where we can asynchronously process post creation and likes to build our search index.

But for simplicity in this design, I’ll assume these APIs directly or indirectly feed into a backend pipeline that updates our storage and indexing layer.


> **With these APIs defined, we can now start to see the basic shape of the system.**

Writes flow in through post creation and like events, and they update our underlying storage and indexing system.

Reads come through the search API, which queries our indexed data and returns ranked results based on either recency or popularity.

At this point, we have a basic end-to-end flow, and next I would start designing the **search indexing system**, since that will be the core challenge of this problem.


> I’ll keep the design API-driven first, and then evolve it into a distributed indexing system once we hit scale constraints.

---
## High-Level Design (Văn nói)

> **Next, I’d like to start with a high-level design. The goal here is to first build a simple end-to-end system that satisfies the functional requirements, and then we can iterate and optimize for scale afterward.**

---

## 1) Write Path: Create & Like Posts

> **Starting with the write path — creating posts and likes.**

We need to support two main actions:

- creating posts
- liking posts

Since this system is part of a larger social network, I’ll assume we already have existing services like a **Post Service** and a **Like Service** that handle authentication, validation, and basic persistence.

So from our perspective in the search system, we mainly care about **ingesting these events**.

A simple way to model this is:

- `Post Service` emits a _PostCreated event_
- `Like Service` emits a _LikeCreated event_

These events are consumed by an **Ingestion Service**, which is responsible for updating our search index.

At this stage, I’ll keep it simple as a single ingestion pipeline, but I do recognize that likes are much higher volume than post creation, so this could become a bottleneck later. I’ll revisit that in the scaling discussion.

---

## 2) Read Path: Search Posts

> **Now moving to the read path — the search functionality.**

When a user searches for posts, the request first goes through an **API Gateway**, which handles authentication, rate limiting, and routing.

Then the request is forwarded to a **Search Service**, which is horizontally scalable. The search service is responsible for:

- parsing the query
- fetching candidate posts
- ranking and returning results

But the key question is: how do we actually find posts by keyword efficiently at scale?

---

## Naive Approach (và lý do không dùng)

> **A naive solution would be to store all posts in a relational database and run a LIKE query over the content.**

For example:

```
SELECT * FROM posts WHERE content LIKE '%keyword%';
```

This would work functionally, but it completely breaks down at scale.

Because the database would need to scan essentially all posts for every query. Even if we shard the database, we’re still doing a distributed full scan, which is too slow for a system with potentially trillions of posts.

So this approach is not viable for our latency requirement.

---

## Correct Approach: Inverted Index

> **Instead, this is a classic use case for an inverted index.**

The idea is to flip the relationship between posts and words.

Instead of:

- post → words

We store:

- word → list of post IDs

So for each keyword, we maintain a list of all posts that contain it.

When a user searches for a keyword, we simply:

- look up the keyword in the index
- retrieve the associated post IDs
- fetch the post metadata
- return results

This makes search extremely fast because we avoid scanning all posts entirely.

---

## Storage Choice (Redis)

> **To make this fast enough for our latency requirement, we can store the inverted index in Redis.**

Redis keeps data in memory, so lookup by keyword is extremely fast — essentially O(1) average access.

So the flow becomes:

- ingestion writes to Redis inverted index
- search reads from Redis and fetches post details from a separate storage system

---

## Ingestion Flow (Tokenization)

> **Now let’s look at how we build the index during ingestion.**

When a post is created, the ingestion service:

1. takes the post content
2. tokenizes it into individual keywords
3. for each keyword, appends the post ID into the corresponding Redis list

So effectively:

- one post becomes many keyword → postID mappings

This allows us to later retrieve posts efficiently by keyword.

---

## Key Challenges (để mở deep dive)

> **There are a few important challenges in this design that I’d like to call out.**

First, **fan-out on write**:

- a single post can generate tens or even hundreds of tokens
- meaning we have many writes per post

Second, **hot keys problem**:

- very common keywords like “love”, “food”, or “news”
- will have extremely large post ID lists
- which can become a performance and storage bottleneck

Third, **data size explosion**:

- we are effectively duplicating references to posts across many keyword lists
- so storage will grow significantly

Finally, **Redis memory limitations and durability concerns**:

- since Redis is in-memory, we need to think about persistence and fallback storage strategies later

---

## Transition Sentence (rất quan trọng)

> **So at a high level, we now have a working system: ingestion builds an inverted index, and search queries that index to retrieve matching posts.**

> **Next, I’d like to go deeper into how we handle ranking, scaling the index, and solving the hot-key and storage challenges.**


## 3) Sorting by Recency or Like Count (Văn nói)

> **Next, I’ll focus on the last functional requirement, which is supporting sorting of search results by either recency or like count.**

So for example, if a user searches for “Taylor”, we might want two different behaviors:

- if sorting by **recency**, we return the most recently created posts first
- if sorting by **likes**, we return the most popular posts first

This introduces an additional challenge on top of keyword search, because now we’re not just retrieving matching posts — we also need to **rank them efficiently**.

---

## Naive Approach: Request-Time Sorting

> **Let me start with a naive solution to understand why it doesn’t scale.**

We can reuse our inverted index to get all post IDs for a given keyword.

Then:

1. we fetch all post IDs for that keyword
2. for each post ID, we call the Post Service to get metadata like timestamp or like count
3. we sort the results in the Search Service
4. and finally return the top results

This would technically work.

However, this approach has serious scalability issues.

---

## Challenges of Request-Time Sorting

> **The main issue here is the size of the intermediate result set.**

For very common keywords like “Taylor” or “music”, we could easily have **tens of millions of posts**.

That leads to a few problems:

First, the inverted index returns a huge list, potentially **hundreds of megabytes of post IDs**, which is already expensive to transfer and process.

Second, for each post ID, we would need to make a **remote call to fetch metadata**, which could mean tens of millions of network requests — clearly not feasible.

Third, sorting millions of items at request time would add significant **latency and CPU pressure**, making it impossible to meet our sub-500ms requirement.

So overall, request-time sorting does not scale.

---

## Better Approach: Precomputed Sorted Indexes

> **A better approach is to move the sorting work from request time to write time.**

Instead of computing ranking dynamically, we maintain **two separate indexes per keyword**:

- one index sorted by **recency**
- one index sorted by **like count**

---

## Recency Index

For recency, we can use a simple **Redis list**.

- When a post is created, we append it to the list for each keyword
- Because newer posts are appended at the end, we can simply read from the tail when querying

This makes retrieving the most recent posts very efficient — essentially O(k) for top-k results.

---

## Like Count Index

> **For like-based ranking, we need something more flexible than a list.**

Here, we can use a **Redis sorted set**, where:

- the member is the post ID
- the score is the like count

This allows us to:

- increment like counts efficiently
- automatically maintain ordering by popularity

So when a like event happens, we simply update the score for that post in the sorted set.

---

## Query Flow with Multiple Indexes

> **So at query time, the flow becomes much simpler.**

When a user searches:

1. we retrieve the post IDs for a keyword
2. depending on sort type:
    - if recency → read from the recency list
    - if likes → read from the sorted set
3. we only fetch a small top-K subset instead of all results
4. then return results directly

This avoids heavy computation at request time.

---

## Trade-offs and Challenges

> **Of course, this design introduces some trade-offs.**

First, we are now maintaining **two indexes instead of one**, which roughly doubles storage for our index layer.

Second, likes are very high frequency events. Every like now requires:

- updating the post metadata
- updating the sorted set for potentially many keyword buckets

This creates a **write amplification problem**, especially for popular posts.

Third, there is also a consistency challenge:

- the like count in the index may lag slightly behind the actual value

---

## Transition to Deep Dive

> **So overall, we’ve significantly improved query performance by precomputing ranking, at the cost of higher write complexity and storage.**

> **Next, I would like to go deeper into how we can optimize the write path, especially around handling high-frequency likes and scaling the inverted index efficiently.**

---

## Ghi điểm (optional closing line)

> **This is a classic trade-off: we’re shifting complexity from read time to write time to meet strict latency requirements on search.**