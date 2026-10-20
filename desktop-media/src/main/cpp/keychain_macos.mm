#import <Foundation/Foundation.h>
#import <Security/Security.h>
#include <cstdio>
#include <cstring>

// Provider secrets travel only on stdin/stdout pipes, never argv or shell source.
int main(int argc,char** argv) {
    @autoreleasepool {
        if (argc!=3) { std::fprintf(stderr,"Usage: hearth-keychain get|put|remove credential-id\n");return 2; }
        NSString* account=[NSString stringWithUTF8String:argv[2]];
        if (!account || account.length<1 || account.length>100) return 2;
        NSCharacterSet* allowed=[NSCharacterSet characterSetWithCharactersInString:@"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"];
        if ([account rangeOfCharacterFromSet:[allowed invertedSet]].location!=NSNotFound) return 2;
        NSDictionary* query=@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService:@"com.sal7one.hearth.desktop.providers",(__bridge id)kSecAttrAccount:account};
        OSStatus status=errSecParam;
        if (std::strcmp(argv[1],"put")==0) {
            NSMutableData* input=[NSMutableData data];unsigned char buffer[1024];
            while (size_t count=std::fread(buffer,1,sizeof(buffer),stdin)) {
                if (input.length+count>16384) return 2;[input appendBytes:buffer length:count];
            }
            if (std::ferror(stdin) || input.length==0) return 2;
            NSDictionary* update=@{(__bridge id)kSecValueData:input};
            status=SecItemUpdate((__bridge CFDictionaryRef)query,(__bridge CFDictionaryRef)update);
            if(status==errSecItemNotFound) {
                NSMutableDictionary* item=[query mutableCopy];item[(__bridge id)kSecValueData]=input;
                status=SecItemAdd((__bridge CFDictionaryRef)item,nullptr);
            }
            [input resetBytesInRange:NSMakeRange(0,input.length)];
        } else if (std::strcmp(argv[1],"get")==0) {
            NSMutableDictionary* search=[query mutableCopy];search[(__bridge id)kSecReturnData]=@YES;
            search[(__bridge id)kSecMatchLimit]=(__bridge id)kSecMatchLimitOne;
            CFTypeRef raw=nullptr;status=SecItemCopyMatching((__bridge CFDictionaryRef)search,&raw);
            if(status==errSecSuccess) {
                NSData* data=CFBridgingRelease(raw);
                if(data.length>16384 || std::fwrite(data.bytes,1,data.length,stdout)!=data.length) return 1;
            }
        } else if (std::strcmp(argv[1],"remove")==0) status=SecItemDelete((__bridge CFDictionaryRef)query);
        else return 2;
        if(status==errSecSuccess) return 0;
        if(status==errSecItemNotFound) return 44;
        std::fprintf(stderr,"macOS credential service failed (OSStatus %d)\n",static_cast<int>(status));return 1;
    }
}
