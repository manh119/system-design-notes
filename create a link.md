test
[create a link]
# Consistent Hashing

## Problem

Hash(key) % N causes massive rebalancing when servers are added or removed.

## Solution

#consisstency hash 

## problem 
- frist 
- tại sao ?? 
- [[Cassandra]]
Use a hash ring.

Each node owns a range of hashes.

When a new node joins, only a portion of keys move.

## Advantages

- Less rebalancing
- Horizontal scaling
- Better availability

## Disadvantages

- Hotspots
- Virtual nodes required

## Used By

- [[Redis Cluster]]
- [[DynamoDB]]
- [[Cassandra]]

## Interview

Q: Why not use hash(key) % N?

A: Because almost all keys move when N changes.



## 20 câu hỏi tại sao ?
-- 
# haha
###haha
### hehe

# kafka 




#### heheheh
```why 


#
```