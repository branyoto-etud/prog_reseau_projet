

### Syntax

- Global message : one line
- Direct message : @pseudo message
- Private connection : >pseudo

Escape char '@' and '>' if first char
\>pseudo  <=>  send a global message with the content '>pseudo'
Same with @




### Todo

-Fill ServerPacketReader

Be careful on private connection:
if there are 3 clients (A, B and C)
A trying to connect to B
before B responds, C tries to connect to A
if A and B accept, A is going to be connected with 2 clients instead of 1

### Disclaimer 
The only file that is ensured to be up to date is the Protocole.txt.


