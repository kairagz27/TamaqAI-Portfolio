from . import Database

if __name__ == '__main__':
    Database().migrate()
    print('PostgreSQL schema is current')
