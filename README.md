SVN Pre-Commit Hook
===================

Why:
----------

* For Maven projects, the original dbdata validation rules no longer apply
* For future client/partner enablement, class-level obfuscation is mandatory
* Pre-commit code review is common practice, and hook should be capable of validating log messages
* At the age of JDK1.7 and above, legacy codes should be obsolete (ojdbc14 should also be replaced)

Build:
----------

* mvn clean package
* mvn clean package -Pobfuscate (for class-level obfuscation)

Run:
----------

* Linux:
  - java -Djava.security.egd=file:///dev/urandom -jar svn-precommit-hook.jar "$1" "$2"
* Windows:
  - java -jar svn-precommit-hook.jar "%1" "%2"
  
Optional Parameters:
----------

* **--superusers** (you know what this means)
* **--allowable-statuses** (the default allowable check-in statuses are Opened, Reopened and Active, which can be overridden with this parameter)
    - e.g: --allowable-statuses=Opened,Reopened,Active,Doing
* **--forbidden-suffixes** (files with specified suffixes will be disallowed for check-in)
    - e.g: --forbidden-suffixes=jar,tar,zip
* **--file-size-limit** (files exceeding the maximum size limit will be rejected for check-in)
    - e.g: --file-size-limit=20M
* **--no-check-db** (suppress dbdata validation, so that you are on your own now)
* **--no-check-naming** (suppress naming validation [**whitespaces**])
* **--check-log-message** (thus commit messages must specify "What" and "Reviewed By" information)
    - e.g: after applying, the commit messages will look like below:
    
    ```
    [artf12306] Please fix the unbearable verification codes a.s.a.p
    
    What: Nobody wants to distinguish between chick's eggs and duck's eggs
    Reviewed By: Jesus Christ
    ```
