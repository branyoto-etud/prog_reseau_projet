## Présentation

Ce projet est basé sur le protocole ChatOS, il permet à des clients de discuter 
entre eux via un serveur.  
Il est livré avec 2 Jars executables : un pour le serveur et un pour le client.
Les deux fichiers jars sont dans le dossier [bin](bin).

---
## Lancement

Pour utiliser correctement le projet il faut premièrement démarrer
le serveur en précisant son port d'écoute :  
`java --enable-preview -jar bin/ServerChatOS.jar port`  

Par la suite, vous pourrez démarrer autant de clients que vous souhaitez
en indiquant l'adresse du serveur, son port d'écoute ainsi que 
l'espace de travail (c'est-à-dire là où il enregistrera les fichiers 
et où les autres pourront lire ses fichiers) :  
`java --enable-preview -jar bin/ClientChatOS.jar adresse port dossier`

Vous voilà maintenant avec un client connecté au serveur !

---
## Utilisation

Maintenant que vous êtes connecté au server, vous devez choisir un pseudo. Si ce pseudo
est déjà utilisé par quelqu'un d'autre, vous devrez choisir un autre pseudo.

Une fois votre pseudo validé par le serveur les autres utilisateurs recevront un message
indiquant votre connexion et vous aurez accès aux actions suivantes pour communiquer avec
les autres utilisateurs :
 - `@pseudo message` : vous permettra d'envoyer un message privé à `pseudo`.
 - `/pseudo ressource` : vous permettra d'établir une connexion privée avec `pseudo`.
   Le client demandé pourra accepter ou refuser la demande. 
   S'il l'accepte vous recevrez alors la ressource demandée qui sera soit affiché 
   dans le terminal (dans le cas d'un fichier .txt) ou sauvegardé dans le dossier 
   indiqué au démarrage.
 - Tout autre message sera interprété comme un message général et sera donc transmit à tout le monde.

**Note :**  
    Il est possible de commencer un message par **'@'** ou **'/'** sans qu'il soit interprété comme un message 
    privé ou une connexion privée. Pour cela précéder le symbole d'un **'\\'**.

**Attention :**  
    La façon dont je sauvegarde les fichiers (autre que les .txt) via une connexion privée est
    en mode `append` donc si un fichier du même nom existait déjà il ne sera pas écrasé mais juste
    étendu.

Lors de la déconnexion, toutes les connexions privées seront fermées et les autres
utilisateurs recevront un message indiquant votre déconnexion.

---
## Quelques tests

- Essayez de vous connecter 2 fois avec le même pseudo. 
  Vous recevrez un message vous indiquant que le pseudo est déjà pris.
  Ensuite déconnectez-vous avec le premier client et réessayez de vous connectez 
  avec le second. Vous serez connecté avez le pseudo qui était non valide auparavant.
  
- Envoyez un message privé ou faites un demande de connexion privée avec un utilisateur non existant.
  Vous recevrez un message vous indiquant que ce client n'est pas connecté.
  
- Demandez une connexion privée à partir d'un client (A) et refusez-la chez l'autre client (B).
  Le client A devrait recevoir un message lui indiquant le refus.
  
- Demandez une ressource non existante dans le dossier de ""vie"" de l'autre client.
  Vous recevrez un message vous indiquant que cette ressource n'existe pas.
  
- Faites deux demandes de connexion privée avec le même client. 
  Si la première connexion est acceptée, alors la réponse à la deuxième 
  connexion devrait être instantanée.

- Lors d'une connexion privée demandé un fichier ayant une taille supérieure à 32'768 octets
  (qui est la limite de la taille des paquets envoyés).
  Normalement tout devrait bien se passer et le fichier sera correctement affiché/sauvegardé.

---
## Localisation des parties

### Les contextes 
#### Client

Le client est composé de deux contextes :
- le contexte principal ([ClientChatOS](src/fr/uge/net/tcp/nonblocking/client/ClientChatOS.java).MainContext). Qui permet de gérer les messages
  envoyé et reçu.
- le contexte des connexions privées ([PrivateConnectionContext](src/fr/uge/net/tcp/nonblocking/client/PrivateConnectionContext.java)). Qui permet
  de gérer le demandes de ressources lors des connexions privées.
  Il agit comme un serveur et un client HTTP en fonction de ce qui est reçu.
  Lors de la demande d'une ressource, contrairement à une vraie requête HTTP, on envoie 
  uniquement `GET ressource` (où ressource est la ressource demandée).

Le client contient 2 threads : la console et les opérations d'envoi/réception.
La console ne fait que transmettre la ligne entrée au contexte principale si elle n'est pas vide.

#### Serveur

Le serveur est lui composé de 3 contextes :
- le contexte de connexion ([ServerChatOS](src/fr/uge/net/tcp/nonblocking/server/ServerChatOS.java).ConnectionContext) 
  qui est utilisé lorsqu'un client se connecte et jusqu'à ce qu'il s'authentifie via un pseudo ou un token valide.
- le contexte client ([ServerChatOS](src/fr/uge/net/tcp/nonblocking/server/ServerChatOS.java).ClientContext) qui est 
  utilisé par les clients lorsqu'ils se sont authentifié avec un pseudo. Il leur permet d'envoyer les différents types 
  de messages aux autres clients.
- le contexte de connexion privé ([ServerChatOS](src/fr/uge/net/tcp/nonblocking/server/ServerChatOS.java).PrivateConnection.PrivateConnectionContext)
  qui est utilisé par les clients lorsqu'ils se sont authentifiés avec un token. 
  Il ne fait que transmettre les octets lu du client A vers le client B (et vice-versa).

Le serveur garde en mémoire les clients connecté, les connexions privées en cours, les tokens utilisés et les demandes de connexions privées.

### Les paquets

Les paquets ([Packet](src/fr/uge/net/tcp/nonblocking/packet/Packet.java)) sont le moyen de communication principal utilisé par ce projet.
(principale et non unique puisque les connexions privées ne l'utilisent pas)
Il existe 6 paquets différents (et 11 si on considère les erreurs comme des paquets et non des sous-paquets).
Pour plus d'information sur les paquets et leur utilité, se référer à la [RFC](docs/Protocol.txt).

### Les paquets HTTP

Comme précisé au-dessus, il existe aussi des paquets n'étant pas définis dans la [RFC](docs/Protocol.txt).
Ce sont les paquets HTTP ([HTTPPacket](src/fr/uge/net/tcp/nonblocking/http/HTTPPacket.java)) échangé par les connexions privées.
 
### Reader

Lorsqu'un contexte reçoit des données, elles sont toujours données à un Reader (lecteur).
Les lecteurs sont des elements important qui permettent de convertir une suite d'octet 
sans sens en un objet pouvant être utilisé par la suite.
Il existe 4 lecteurs : (tous implémentant l'interface [Reader](src/fr/uge/net/tcp/nonblocking/reader/Reader.java))
- Un lecteur de chaine de caractère ([StringReader](src/fr/uge/net/tcp/nonblocking/reader/StringReader.java)) qui permet de lire un entier
  représentant la longueur de la chaine de caractère qui le suit encodé en UTF-8.
- Un lecteur de paquet ([PacketReader](src/fr/uge/net/tcp/nonblocking/packet/PacketReader.java)) qui lit un paquet. Donc un octet et en fonction de la valeur
  de cet octet lit différentes choses. (Pour plus de détail lire l'annexe de la [RFC](docs/Protocol.txt)).
- Un lecteur de ligne HTTP ([HTTPLineReader](src/fr/uge/net/tcp/nonblocking/http/HTTPLineReader.java)) qui lit des octets jusqu'à lire `\r\n` (qui indique une fin de ligne en HTTP).
- Un lecteur de paquet HTTP ([HTTPReader](src/fr/uge/net/tcp/nonblocking/http/HTTPReader.java)) qui lit un message HTTP pouvant être une requête ou une réponse.
- Un lecteur de rejet ([RejectReader](src/fr/uge/net/tcp/nonblocking/reader/RejectReader.java)) qui contrairement aux autres rejette les octets lu jusqu'à lire une récupération d'erreur.
