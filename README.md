

### Syntax

- Global message : one line
- Direct message : @pseudo message
- Private connection : /pseudo file

Escape char '@' and '/' if first char
\/pseudo  <=>  send a global message with the content '/pseudo'
Same with @

### Todo

- Client : Only tries to authenticate once at the time
        -> Wait for AUTH packet or AUTH_ERROR packet to send another AUTH packet

### Disclaimer 
The only file that is ensured to be up to date is the Protocole.txt.


