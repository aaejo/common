use jds;

drop table if exists originalfile;
drop table if exists scrapeddata;

create table originalfile(
    personID varchar(255),
    salutation varchar(10),
    fName varchar(255),
    mName varchar(255),
    lName varchar(255),
    address1 varchar(255),
    address2 varchar(255),
    address3 varchar(255),
    city varchar(255),
    state varchar(255),
    postalCode varchar(255),
    country varchar(255),
    department varchar(255),
    institution varchar(255),
    institutionId varchar(255),
    primeEmail varchar(255),
    userId varchar(255),
    ORCID varchar(255),
    ORCIDVal varchar(255),
    personAttribute varchar(255),
    memberStatus varchar(255));

create table scrapeddata(
    salutation varchar(10),
    fName varchar(255),
    mName varchar(255),
    lName varchar(255),
    address1 varchar(255),
    address2 varchar(255),
    address3 varchar(255),
    city varchar(255),
    state varchar(255),
    postalCode varchar(255),
    country varchar(255),
    department varchar(255),
    institution varchar(255),
    primeEmail varchar(255),
    userID varchar(255),
    personAttribute varchar(255),
    constraint scrapeddata_pk primary key (primeEmail, personAttribute));
