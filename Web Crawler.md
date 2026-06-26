# Functional requirements 

1. Crawl the web starting from a given set of seed URLs.
2. Extract text data from each web page and store the text for later processing.

# NonFunctional requirements

1. Scalable to crawl 1B webpage a day
2. Prefer availability > consistency
3. No duplicate content crawled
4. Fault tolerance, if one machine die while crawling, we can restart quickly from processing part
5. respect robots.txt. file, and rate limiter policy of website 

# Entity

1. Webpage
2. URL 

# System interface

- Input : seeds URLs 
- Output : Extracted text data from webpages crawled as much as possible 

# Data flow 

1. with list of seed URLs, we add it to URL table
2. we are going to make a HTTP request to URLs with status = PENDING in URL table
3. With each HTML page, we extract content text and URLs in that page
4. We deduplicate page content and URL
5. We add new URLs in that page to URL table for latter processing and mark processed URL as PROCESSED, and save extracted text and webpage meta data
6. reloop from step 2

# High level design 

## What is a simple, high-level design for the system that allows us to start from seed urls and crawl the web?

![[Pasted image 20260625063440.png]]

Steps are : 
1. Fetch service get URL have status = NEW from URL table
2. Fetch service get HTML from internet of that URLs
3. Extract service extract text and URL of those HTML
4. save text in s3 and get content_url
5. mark status of that urls as processed, and save webpage metadata and new URLs

# Deep dive
## How can you ensure the system retries URLs that failed to download?

![[Pasted image 20260626062426.png]]

- When Fetch service get HTML from a URL, if get http code status = 4xx, we stop retry download that URL 
- When get http code status = 5xx, we save that url with status = RETRYABLE, next_attempt = now + exponential backoff, and value of exponenetial backoff, num_retry. 
- the next time Fetch service get URL for crawling, it include url have status NEW and RETRYABLE

## How would you implement exponential backoff and max retry count in Cassandra so that multiple fetch workers do not repeatedly pick the same failing URL at the same time?

![[Pasted image 20260626062841.png]]

- we can use Redis as distributed lock (set NX command) for saving urls that are processing. We can save id of that URL as key with TTL = 5 minute 
- the next time Fetch service get URL for crawling, it first check if that url have in Redis, we ignore it for this crawling. 
- when Fetch service get URL for crawling, it include url have status NEW or RETRYABLE and now > next_attempt. If after max_retry, we will mark that URL as fail. 

## How can you ensure politeness and adhere to robots.txt?

![[Pasted image 20260626064515.png]]

- When Fetching HTML from a url, we save robots.txt in domain table for that domain
- When get a URL for fetching, we first check the policy of that domain like rate-limiting, allow to crawl, ... 

## How can you avoid crawling duplicate content across different URLs?

![[Pasted image 20260626071037.png]]

- Nếu webpage có 10k byte thì hash của nó dài ko nhỉ? Nếu ngắn, đơn giản là lưu hash trong db và đánh hash index để tìm kiếm O(1)
- Và nếu hash thì nên dùng thuật toán hash nào? và nên hash cả file html hay chỉ cần file text. Và có thuật toán nào hashs để so sánh độ tương đồng 90% không ? 
- We can have a deduplicate service to hash the text of that URL, and save it in URL tables, create hash index on that field. When we are processing a URL, we check if hash of that context is existed, if existed we ignore it and mark status of that URL as DUPLICATE

## How would you implement parallelization to ensure the system could efficiently crawl 10 billion pages in under 5 days? Think deeply about any bottlenecks.

- each step is done, and we can process another url
- with fetch service, we can mark max depth of a website to avoid trap
- between extract service and deduplicate service we can have a kafka to scale producer and consumer (deduplicate service) independantly. 

## How would you modify the system to ensure the extracted text data remains up-to-date? Consider that we now require the data to be fresh and regularly updated.

![[Pasted image 20260626074750.png]]

- we may have a cronjob run each 10 days, to re-fetch url that are done.
