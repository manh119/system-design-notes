
## search text dùng inverted index cũng chỉ có vài loại 

- Inverted index chẳng qua là đảo ngược token -> list postId 
- HashMap O(1) : tìm chính xác token -> list postId, tốn ko gian vì các từ gần giống nhau cũng lưu token riêng biệt, ko tìm được tiền tố 
- B+ tree : list các token đã được sắp xếp theo alphabet -> tìm theo token thì duyệt trên cây đó 
- Trie : giúp tối ưu không gian lưu trữ 
- FST (Finite state Transducer - cách của elastic search) - giống Trie, gọn nằm hết được trong RAM  