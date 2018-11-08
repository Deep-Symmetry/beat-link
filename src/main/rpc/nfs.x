/*
 * demo.x -- demonstrates and tests some jrpcgen features. It doesn't
 *           boil down to a useful example, it just checks a few things
 *           within code generation.
 *
 * To compile, use
 *   java -jar jrpcgen.jar -p tests.org.acplt.oncrpc.jrpcgen -nobackup demo.x
 */

typedef string STRING<>;
typedef STRING STRINGVECTOR<>;

/* Check that dependencies are followed when dumping constants */
const C_B = C_A;
const C_A = C_C;
const C_C = 42;

/* Check enumerations */
enum ENUMBAZ { E_BAZ = C_B };

enum ENUMFOO {
    FOO, BAR, BAZ_1 = C_B, BAZ_2, BAZ_3 = E_BAZ
};

const FIXEDBUFFERLENGTH = 64;

struct LINKEDLIST {
    int foo;
    struct LINKEDLIST *next;
};

struct TREE {
	string key<>;
	string value<>;
	struct TREE *left;
	struct TREE *right;
};

union UNION switch ( bool okay ) {
    case TRUE:
        LINKEDLIST *list;
    default:
        void;
};

union ANSWER switch ( int value ) {
    case 40:
    case 41:
        int wrong;
    case 42:
        int the_answer;
    default:
        int check_hash;
};

struct SILLYSTRUCT {
    char fixedbuffer[512];
    char buffer<128>;
    opaque fixedbytes[FIXEDBUFFERLENGTH];
    opaque bytes<32>;
    unsigned int ui1;
    unsigned ui2;
    string nonsense<>;
};

struct SOMERESULT {
	int error;
	string typedesc<>;
	opaque data<>;
};

program DEMO {
    version FIRST_DEMO_VERSION {
        void NULL(void) = 0;
        string echo(string) = 1;
        string concat(STRINGVECTOR) = 2;
        bool checkfoo(ENUMFOO) = 3;
        ENUMFOO foo(void) = 4;
        LINKEDLIST ll(LINKEDLIST) = 5;
        SOMERESULT readSomeResult(void) = 42;
    } = 1;
    version SECOND_DEMO_VERSION {
        void NULL(void) = 0;
        string cat(string, string) = 42;
        string cat3(string one, string two, string three) = 43;
        string checkfoo(ENUMFOO foo) = 3;
        LINKEDLIST llcat(LINKEDLIST l1, LINKEDLIST l2) = 55;
        void test(string a, ENUMFOO b, ENUMFOO c, int d) = 100;
    } = 2;
} = 0x20049678;

/* End of file demo.x */

