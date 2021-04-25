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

Vous trouverez ci-dessous la hiérarchie des dossiers du projet :

```
projet_prog_reseau
 |-  assets                     # Groupement des "espace de travail" pour plusieurs utilisateurs  
 |    |-  adam                  # Répertoire réservé à l'utilisateur "adam"  
 |    |    |-  hello.txt        # Fichier contenant un message basique en UTF-8  
 |    |-  bruce                 # Répertoire réservé à l'utilisateur "bruce"  
 |    |    |-  1Mio.dat         # Fichier ayant une taille supérieure à la taille des buffer  
 |    |    |-  hello.txt        # Fichier contenant un message basique en UTF-8  
 |    |-  michel                # Répertoire réservé à l'utilisateur "michel"  
 |    |    |-  hello.txt        # Fichier contenant un message basique en UTF-8  
 |-  bin                        # Conteneur des executables  
 |    |-  ClientChatOS.jar      # Executable du client  
 |    |-  ServerChatOS.jar      # Executable du serveur  
 |-  docs                       # Contient tout ce qui est en rapport avec la documentation  
 |    |-  javadoc               # Dossier contenant la documentation compilée du projet  
 |    |-  ChatOS - Manuel.pdf   # Manuel d'utilisation du projet  
 |    |-  ChatOS - Rapport.pdf  # Rapport détaillé sur l'architecture du projet  
 |    |-  Protocol.txt          # Protocole sur lequel est basé le système d'échange de paquets  
 |-  out                        # Contient les fichiers produit par le projet  
 |-  src                        # Contient les fichiers source du projet  
 |-  README.md                  # Ce fichier  
```