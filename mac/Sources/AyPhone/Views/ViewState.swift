import SwiftUI

/// Drop-in for `@State`. On recent SDKs `@State` is a macro whose plugin ships only
/// with full Xcode, so this keeps the app buildable with the Command Line Tools alone.
/// SwiftUI finds the nested `State` storage through `DynamicProperty`.
@propertyWrapper
struct ViewState<Value>: DynamicProperty {
    private var storage: State<Value>

    init(wrappedValue: Value) {
        storage = State(initialValue: wrappedValue)
    }

    var wrappedValue: Value {
        get { storage.wrappedValue }
        nonmutating set { storage.wrappedValue = newValue }
    }

    var projectedValue: Binding<Value> { storage.projectedValue }
}
